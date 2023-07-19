//! Example crate.

pub use connection::ConnectionInfo;
pub use connection::ConnectionType;
pub use connection::ConnectionInfoBuilder;
pub use discovered_device::DiscoveredDevice;
pub use discovered_device::DiscoveredDeviceBuilder;


/// Main function.
fn main() {
    println!("Remote connectivity manager");
    let conn_info: ConnectionInfo = ConnectionInfoBuilder::new()
        .set_connection_type(ConnectionType::Bluetooth)
        .set_peer_address(String::from("address"))
        .set_connection_id(0)
        .build();
    // println!("connectioninfo: {:?}{:?}{:?}", conn_info._connection_type, conn_info._connection_id, conn_info._peer_address");

    let device: DiscoveredDevice = DiscoveredDeviceBuilder::new()
        .set_display_name(String::from("test"))
        .set_connection_info(conn_info)
        .build();
    println!("Discovered device: {:?}", &device);
}