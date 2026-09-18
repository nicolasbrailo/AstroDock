package com.nicobrailo.alauncher.media

import android.service.notification.NotificationListenerService

// Does nothing by itself: reading the active media sessions needs the
// notification access permission, and that permission is granted to a
// notification listener. NowPlaying passes this service's name to the media
// session manager as proof it has it. The user grants it in the System tab of
// the settings.
class MediaListenerService : NotificationListenerService()
