// Mixi Chat message notifications. Uses the verified source-export schema:
// /messages/{sortedUidPair}/{pushId}, /relationships/{pairId}/status,
// and the migrated per-installation token records under /users/{uid}/fcmTokens.
// Do not deploy until these token rules and the target Firebase project are
// configured and verified in the Firebase Console.
const crypto = require('node:crypto');
const { onValueCreated } = require('firebase-functions/v2/database');
const { initializeApp } = require('firebase-admin/app');
const { getDatabase } = require('firebase-admin/database');
const { getMessaging } = require('firebase-admin/messaging');
const logger = require('firebase-functions/logger');

initializeApp();

const CLAIM_LEASE_MS = 2 * 60 * 1000;
const TOKEN_BATCH_SIZE = 500;
const MAX_DISPATCH_ATTEMPTS = 8;
const INVALID_TOKEN_CODES = new Set([
  'messaging/registration-token-not-registered',
  'messaging/invalid-registration-token',
]);

function digest(value) {
  return crypto.createHash('sha256').update(value).digest('hex');
}

exports.notifyRecipient = onValueCreated(
  {
    ref: '/messages/{pairId}/{messageId}',
    instance: 'mixi-chat-final-default-rtdb',
    retry: true,
  },
  async (event) => {
    const message = event.data.val();
    if (!message || typeof message.senderId !== 'string' || typeof message.receiverId !== 'string') return;
    if (!message.senderId || !message.receiverId || message.senderId === message.receiverId) return;
    if (message.isDeletedForEveryone === true) return;

    // ChatActivity sorts the two authenticated UIDs and joins them with '_'.
    const pairId = [message.senderId, message.receiverId].sort().join('_');
    if (event.params.pairId !== pairId) {
      logger.warn('Ignoring message with mismatched pair ID', {
        pairId: event.params.pairId,
        messageId: event.params.messageId,
      });
      return;
    }

    const db = getDatabase();
    const relationship = await db.ref(`/relationships/${pairId}`).get();
    if (!relationship.exists() || relationship.child('status').val() !== 'friends') {
      logger.info('Ignoring message without an active friend relationship', {
        pairId,
        messageId: event.params.messageId,
      });
      return;
    }

    // Claim is scoped to both pair and message. A retry can take over only an
    // expired/retryable lease; sent claims are terminal.
    const claimKey = digest(`${pairId}\n${event.params.messageId}`);
    const claimRef = db.ref(`/notificationClaims/${claimKey}`);
    const owner = crypto.randomUUID();
    const now = Date.now();
    const claimResult = await claimRef.transaction((current) => {
      if (current && current.state === 'sent') return;
      if (current && current.state === 'processing' && Number(current.leaseUntil) > now) return;
      if (current && Number(current.attempts) >= MAX_DISPATCH_ATTEMPTS) {
        return { ...current, state: 'exhausted', owner: null, leaseUntil: 0 };
      }
      return {
        state: 'processing',
        owner,
        leaseUntil: now + CLAIM_LEASE_MS,
        attempts: Number(current && current.attempts ? current.attempts : 0) + 1,
        sentTokenHashes: current && current.sentTokenHashes ? current.sentTokenHashes : {},
      };
    });
    const claim = claimResult.snapshot.val();
    if (claim && claim.state === 'exhausted') {
      logger.error('Notification retries exhausted', { pairId, messageId: event.params.messageId });
      return;
    }
    if (!claimResult.committed || !claim || claim.owner !== owner) return;

    try {
      const tokenSnapshot = await db.ref(`/users/${message.receiverId}/fcmTokens`).get();
      const tokenRecords = tokenSnapshot.val();
      const deviceTokens = tokenRecords && typeof tokenRecords === 'object'
        ? Object.entries(tokenRecords)
          .filter(([, record]) => record && typeof record === 'object' &&
            typeof record.token === 'string' && record.token.length > 0 && record.disabled !== true)
          .map(([installationId, record]) => ({ installationId, token: record.token }))
        : [];
      if (deviceTokens.length === 0) {
        // Keep this retryable so a token registered shortly after message
        // creation can still receive it. Function retry duration is bounded
        // by Cloud Functions' retry policy.
        throw Object.assign(new Error('No valid recipient device tokens'), { code: 'no-recipient-tokens' });
      }

      const previousSuccesses = claim.sentTokenHashes || {};
      const pending = deviceTokens.filter(({ installationId }) => !previousSuccesses[digest(installationId)]);
      let accepted = 0;
      let failed = 0;
      let retryNeeded = false;
      const successfulHashes = {};
      const invalidDevices = [];

      for (let offset = 0; offset < pending.length; offset += TOKEN_BATCH_SIZE) {
        const batch = pending.slice(offset, offset + TOKEN_BATCH_SIZE);
        const response = await getMessaging().sendEachForMulticast({
          tokens: batch.map((item) => item.token),
          data: {
            type: 'chat_message',
            pairId,
            messageId: String(event.params.messageId),
            senderId: message.senderId,
            receiverId: message.receiverId,
          },
          android: { priority: 'high' },
        });

        response.responses.forEach((result, index) => {
          const device = batch[index];
          if (result.success) {
            accepted++;
            successfulHashes[digest(device.installationId)] = true;
          } else {
            failed++;
            if (result.error && INVALID_TOKEN_CODES.has(result.error.code)) {
              invalidDevices.push(device);
            } else {
              retryNeeded = true;
            }
          }
        });
      }

      // Persist accepted installations before retrying failures, so successful
      // devices are not deliberately sent the notification again.
      if (Object.keys(successfulHashes).length) {
        await claimRef.update({
          sentTokenHashes: { ...previousSuccesses, ...successfulHashes },
        });
      }
      // Compare the current token before deleting, so a concurrent onNewToken
      // update for the same installation cannot be removed accidentally.
      await Promise.all(invalidDevices.map(({ installationId, token }) =>
        db.ref(`/users/${message.receiverId}/fcmTokens/${installationId}`).transaction((current) => {
          if (current && current.token === token) return null;
          return;
        }),
      ));

      if (retryNeeded) {
        throw Object.assign(new Error(`FCM rejected ${failed} device message(s)`), { code: 'partial-fcm-failure' });
      }
      await claimRef.update({ state: 'sent', owner: null, leaseUntil: 0, completedAt: Date.now() });
      logger.info('FCM accepted message notification dispatch', {
        pairId,
        messageId: event.params.messageId,
        acceptedDevices: accepted,
        failedDevices: failed,
      });
    } catch (error) {
      const current = (await claimRef.get()).val();
      if (current && current.owner === owner) {
        await claimRef.update({ state: 'retryable', owner: null, leaseUntil: 0 });
      }
      logger.error('FCM notification dispatch failed', {
        pairId,
        messageId: event.params.messageId,
        code: error && error.code ? error.code : 'dispatch-failed',
      });
      throw error;
    }
  },
);
