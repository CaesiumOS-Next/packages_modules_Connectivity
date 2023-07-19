//! Event listener interface to receive messages from the peer device.
#![allow(dead_code)]

/// Interface that exposes the ability to receive events on the communication channel
/// with the peer device.
trait EventListener {
    /// Called when a new message is received from the peer device.
    fn onMessageReceived(request: ActiveUnlockRequest) -> ActiveUnlockResponse;

    /// Called when the remote device is disconnected.
    fn onDisconnect();
}
