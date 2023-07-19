pub mod remote_connectivity_manager;

trait EventListener {
    /// Called when a new message is received from the peer device.
    fn onMessageReceived(request: ActiveUnlockRequest) -> ActiveUnlockResponse;

    /// Called when the remote device is disconnected.
    fn onDisconnect();
}
