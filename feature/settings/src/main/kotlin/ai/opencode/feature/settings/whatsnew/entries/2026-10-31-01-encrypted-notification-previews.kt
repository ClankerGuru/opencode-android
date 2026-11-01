package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.encryptedNotificationPreviews: WhatsNewEntry
    get() = smaller(
        id = "encrypted-notification-previews",
        day = "2026-10-31",
        title = "Encrypted Notification Previews",
        symbol = "lock.fill",
        summary = "With the updated notification plugin and an HTTPS connection, your server encrypts the title and message for this device. If a preview can’t be opened, the notification just says “New activity.”",
        tryIt = Destination.notifications,
    )
