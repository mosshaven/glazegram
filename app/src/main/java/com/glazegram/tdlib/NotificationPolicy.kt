package com.glazegram.tdlib

/**
 * Pure resolution of *effective* chat mute state, independent of Android/TDLib runtime wiring so
 * it can be unit-tested. TDLib splits mute into a per-chat [ChatNotificationSettings] that may
 * defer to a per-scope [ScopeNotificationSettings] via `useDefaultMuteFor`.
 */
object NotificationPolicy {
    /**
     * The three TDLib notification scopes. A chat maps to exactly one, decided by its TDLib chat
     * type (not a UI guess): private/secret -> [PRIVATE], basic group + non-channel supergroup ->
     * [GROUP], channel supergroup -> [CHANNEL].
     */
    enum class Scope { PRIVATE, GROUP, CHANNEL }

    /**
     * Effective mute:
     * - `useDefaultMuteFor == false` -> the chat's own `muteFor`;
     * - otherwise -> the scope's `muteFor` (null scope = not yet known -> treat as unmuted).
     *
     * A positive `muteFor` (seconds remaining) means muted. Unread count is intentionally not an
     * input: an unread chat can still be muted.
     */
    fun isEffectivelyMuted(
        useDefaultMuteFor: Boolean,
        chatMuteFor: Int,
        scopeMuteFor: Int?,
    ): Boolean =
        if (!useDefaultMuteFor) {
            chatMuteFor > 0
        } else {
            (scopeMuteFor ?: 0) > 0
        }
}
