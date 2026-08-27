package com.glazegram.tdlib

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPolicyTest {

    @Test
    fun explicitChatMuteIsMutedRegardlessOfScope() {
        // useDefaultMuteFor == false, chat muteFor > 0 -> muted even if scope is unmuted.
        assertTrue(
            NotificationPolicy.isEffectivelyMuted(
                useDefaultMuteFor = false,
                chatMuteFor = 3600,
                scopeMuteFor = 0,
            ),
        )
    }

    @Test
    fun explicitChatUnmuteIsUnmutedEvenWhenScopeIsMuted() {
        // useDefaultMuteFor == false, chat muteFor == 0 -> unmuted even if scope is muted.
        assertFalse(
            NotificationPolicy.isEffectivelyMuted(
                useDefaultMuteFor = false,
                chatMuteFor = 0,
                scopeMuteFor = 3600,
            ),
        )
    }

    @Test
    fun inheritedMutedScopeMutesTheChat() {
        // useDefaultMuteFor == true -> scope wins; scope muted.
        assertTrue(
            NotificationPolicy.isEffectivelyMuted(
                useDefaultMuteFor = true,
                chatMuteFor = 0,
                scopeMuteFor = 3600,
            ),
        )
    }

    @Test
    fun inheritedUnmutedScopeLeavesTheChatUnmuted() {
        // useDefaultMuteFor == true -> scope wins; scope unmuted (chat's own muteFor ignored).
        assertFalse(
            NotificationPolicy.isEffectivelyMuted(
                useDefaultMuteFor = true,
                chatMuteFor = 3600,
                scopeMuteFor = 0,
            ),
        )
    }

    @Test
    fun unknownScopeIsTreatedAsUnmutedWhenInheriting() {
        assertFalse(
            NotificationPolicy.isEffectivelyMuted(
                useDefaultMuteFor = true,
                chatMuteFor = 3600,
                scopeMuteFor = null,
            ),
        )
    }
}
