#  Copyright (C) 2024 The Android Open Source Project
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
from ipaddress import IPv4Address

ETHER_BROADCAST_MAC_ADDRESS = "FF:FF:FF:FF:FF:FF"
ARP_REQUEST_OP = 1
ARP_REPLY_OP = 2

arp_template = (
        "FFFFFFFFFFFF" + # DA
        "000000000000" + # SA
        "0806" + # ARP
        "0001" + # Hardware type (Ethernet)
        "0800" + # Protocol type (IPv4)
        "06" + # hardware address length
        "04" + # protocol address length
        "0000" + # opcode
        "000000000000" + # sender MAC
        "00000000" + # sender IP
        "000000000000" + # target MAC
        "00000000" # target IP
)

def construct_arp_request(src_mac, src_ip, dst_ip, dst_mac=ETHER_BROADCAST_MAC_ADDRESS) -> str:
    """Constructs an ARP request packet as a hexadecimal string.

    This function creates an ARP request packet by filling in the necessary fields
    in a predefined ARP packet template. It takes the source MAC address, source IP
    address, and destination IP address as input. The destination MAC address
    defaults to the broadcast MAC address to send the request to all devices on the
    local network.

    Args:
    src_mac: The MAC address of the sender.
    src_ip: The IP address of the sender.
    dst_ip: The IP address of the target machine.
    dst_mac: The MAC address of the recipient (defaults to broadcast MAC address).

    Returns:
    A string representing the ARP request packet in hexadecimal format.
    """
    # Replace the needed fields from packet template
    arp_request = (
            dst_mac.replace(":","") +
            src_mac.replace(":","") +
            arp_template[24:40] +
            str(ARP_REQUEST_OP).rjust(4, "0") +
            src_mac.replace(":","") +
            hex(int(IPv4Address(src_ip)))[2:] +
            "000000000000" +
            hex(int(IPv4Address(dst_ip)))[2:]
    )

    # always convert to upper case hex string
    return arp_request.upper()


def construct_arp_reply(src_mac, dst_mac, src_ip, dst_ip) -> str:
    """Constructs an ARP reply packet as a hexadecimal string.

    This function creates an ARP reply packet by filling in the required fields
    in a predefined ARP packet template. It takes the source MAC address,
    destination MAC address, source IP address, and destination IP address
    as input.

    Args:
    src_mac: The MAC address of the sender.
    dst_mac: The MAC address of the recipient.
    src_ip: The IP address of the sender.
    dst_ip: The IP address of the target machine.

    Returns:
    A string representing the ARP reply packet in hexadecimal format.
    """
    # Replace the needed fields from packet template
    arp_reply = (
            dst_mac.replace(":","") +
            src_mac.replace(":","") +
            arp_template[24:40] +
            str(ARP_REPLY_OP).rjust(4, "0") +
            src_mac.replace(":","") +
            hex(int(IPv4Address(src_ip)))[2:] +
            dst_mac.replace(":", "") +
            hex(int(IPv4Address(dst_ip)))[2:]

    )

    # always convert to upper case hex string
    return arp_reply.upper()