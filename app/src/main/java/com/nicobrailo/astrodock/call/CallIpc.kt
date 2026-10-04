package com.nicobrailo.astrodock.call

// What CallActivity (in the :call process) and CallSignalService (in the main
// one) say to each other over their Messengers. The main process holds the
// only MQTT connection, so everything the call has to tell the other device
// goes through it, and so does everything the other device says back.
object CallIpc {
    // Activity to service. Every one carries KEY_CALL_ID.
    // The activity is up; replyTo is where to reach it
    const val MSG_ATTACH = 1
    // Our session description (the offer when calling, the answer when
    // called), with every candidate in it: KEY_SDP
    const val MSG_LOCAL_SDP = 2
    // Media is flowing
    const val MSG_CONNECTED = 3
    // The call is over on this side (hung up, or the connection failed).
    // KEY_END_REASON is END_NO_CONNECTION if the media never got through,
    // so the other side can say so rather than that we hung up, or
    // RejectReason.DECLINED's wire name if it was turned down while it rang.
    const val MSG_ENDED = 4
    // An incoming call was answered, by hand or by itself, so it no longer
    // rings; the answer itself follows as MSG_LOCAL_SDP once it has its
    // candidates, which can take seconds
    const val MSG_ANSWERING = 5

    // Service to activity
    // The other side took our offer and is ringing
    const val MSG_REMOTE_RINGING = 100
    // The other side's answer: KEY_SDP
    const val MSG_REMOTE_ANSWER = 101
    // The call is over, and why: KEY_END_REASON
    const val MSG_END = 102

    const val KEY_CALL_ID = "call_id"
    const val KEY_SDP = "sdp"
    const val KEY_END_REASON = "end_reason"

    // Why the call ended, for the activity to show. A rejection is its
    // RejectReason's wire name.
    // The other side hung up
    const val END_HANGUP = "hangup"
    // We called, and the other side never answered
    const val END_NO_ANSWER = "no_answer"
    // Both sides agreed to the call, but the media never got through: the
    // devices can't reach each other on the network. Also sent to the other
    // device as a hangup's reason.
    const val END_NO_CONNECTION = "no_connection"
    const val END_UNREACHABLE = "unreachable"
    // The main process doesn't know this call (it ended before the activity
    // came up, or the process restarted)
    const val END_UNKNOWN = "unknown"
}
