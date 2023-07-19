//! Example crate.

pub use connection_info::ConnectionInfo;
pub use connection_info::ConnectionType;
pub use connection_info::ConnectionInfoBuilder;
pub use discovered_device::DiscoveredDevice;
pub use discovered_device::DiscoveredDeviceBuilder;
pub use discovery_filter::DiscoveryFilter;
pub use discovery_filter::DiscoveryFilterBuilder;

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

    let filter: DiscoveryFilter = DiscoveryFilterBuilder::new()
        .set_device_name(String::from("Device-a"))
        .set_address(String::from("address"))
        // .set_capability()
        .build();
    println!("Discovery filter: {:?}", &filter);
}