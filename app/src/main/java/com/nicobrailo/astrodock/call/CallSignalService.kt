package com.nicobrailo.astrodock.call

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log

// The main process's end of a call: CallActivity, in the :call process, binds
// to this and hands over what it needs sent, and CallRouter answers through
// the Messenger the activity attached with (see CallIpc).
//
// If the :call process dies, the router has to hang up for it, or the other
// device would wait for a hangup that never comes; linkToDeath is how it
// finds out.
class CallSignalService : Service() {
    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val router = CallRouter.get(this@CallSignalService)
            val callId = msg.data?.getString(CallIpc.KEY_CALL_ID) ?: return
            when (msg.what) {
                CallIpc.MSG_ATTACH -> {
                    val session = msg.replyTo ?: return
                    try {
                        session.binder.linkToDeath({ post { router.onSessionDied(callId) } }, 0)
                    } catch (e: Exception) {
                        // Already dead
                        Log.w(TAG, "Call $callId went away as it attached", e)
                        router.onSessionDied(callId)
                        return
                    }
                    router.onAttached(callId, session)
                }
                CallIpc.MSG_LOCAL_SDP -> msg.data.getString(CallIpc.KEY_SDP)?.let { router.onLocalSdp(callId, it) }
                CallIpc.MSG_CONNECTED -> router.onConnected(callId)
                CallIpc.MSG_ENDED -> router.onEnded(callId)
            }
        }
    }
    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    companion object {
        private const val TAG = "CallSignalService"
    }
}
