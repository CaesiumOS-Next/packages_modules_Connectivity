#!/bin/bash
# Dev script to auto-detect and install clat program/maps for ipv6-only nat64/dns64 network.
#
# Depends on (at least):
#   bpftool, iproute2 (ip, tc)
#   bash, coreutils (chmod, cut, printf, sort, tr), host, sed
#
# bash likely needs to be relatively new and full featured
# host can be found in bind-utils or bind9-host or something like that
#
# This can trigger a kernel bug where permanent 'ip -6 neigh add proxy'
# entries get deleted on link flap, hence it creates the /tmp/neigh-proxy.sh
# script to re-add...
#
# Upstream (unresolved) Linux kernel thread:
#   https://lore.kernel.org/netdev/20221214232059.760233-1-decot+git@google.com/T/

set -e
set -u

pfx96() {
  host -t AAAA ipv4only.arpa | sed -rn 's@^ipv4only[.]arpa has IPv6 address ([0-9a-f:]+)::c000:a[ab]@\1::@p' | sort -u
}

gt() {
  ip -6 route get "$1" | sed -rn 's@^.* via ([0-9a-f:]+) dev ([a-z0-9]+) .* src ([0-9a-f:]+) .*$@\'$2'@p'
}

be16() {
  local u
  for u in "$@"; do
    local b0=$[(u >>  0) & 0xFF]
    local b1=$[(u >>  8) & 0xFF]
    printf "%d %d " "${b1}" "${b0}"
  done
}

u16() {
  local u
  for u in "$@"; do
    local b0=$[(u >>  0) & 0xFF]
    local b1=$[(u >>  8) & 0xFF]
    printf "%d %d " "${b0}" "${b1}"
  done
}

declare -r PFX96=$(pfx96)
declare -r GW=$(gt "${PFX96}" 1)
declare -r DEV=$(gt "${PFX96}" 2)
declare -r SRC=$(gt "${PFX96}" 3)
declare -r IFINDEX=$(< "/sys/class/net/${DEV}/ifindex")
declare -r MTU6=$(< "/proc/sys/net/ipv6/conf/${DEV}/mtu")
declare -r MTU4=$[MTU6-28]
declare -r MAC=$(< "/sys/class/net/${DEV}/address")
echo "PFX96[${PFX96}] GW[${GW}] DEV[${DEV}] IFINDEX[${IFINDEX}] SRC[${SRC}] MTU6[${MTU6}] MTU4[${MTU4}] MAC[${MAC}]"

v6() {
  local -r S="$1"
  local -r S1=${S%:*:*:*:*:*:*:*}
  local -r S2=${S%:*:*:*:*:*:*}
  local -r S3=${S%:*:*:*:*:*}
  local -r S4=${S%:*:*:*:*}
  local -r S5=${S%:*:*:*}
  local -r S6=${S%:*:*}
  local -r S7=${S%:*}
  echo $[0x${S1}] $[0x${S2#*:}] $[0x${S3#*:*:}] $[0x${S4#*:*:*:}] $[0x${S5#*:*:*:*:}] $[0x${S6#*:*:*:*:*:}] $[0x${S7#*:*:*:*:*:*:}] $[0x${S#*:*:*:*:*:*:*:}]
}

echo -n "SRC:    "
v6 "${SRC}"

declare -r LOCAL4=192.0.0.1
declare -a L=($(v6 "${SRC}"))

L[4]=$RANDOM
L[5]=$RANDOM
L[6]=$RANDOM
L[7]=0

echo "NEW: ${#L[*]}: ${L[*]}"
LL=$[0x64+0xff9b+${L[0]}+${L[1]}+${L[2]}+${L[3]}+${L[4]}+${L[5]}+${L[6]}+${L[7]}+65535-192*256+65535-1]
L[7]=$[65535-LL%65535]
LL=$[0x64+0xff9b+${L[0]}+${L[1]}+${L[2]}+${L[3]}+${L[4]}+${L[5]}+${L[6]}+${L[7]}+65535-192*256+65535-1]
echo $[LL%65535]

PREEXIST=false

declare -a LP=($(ip -6 neigh show proxy | cut -d' ' -f1 | tr ':' ' ' | sed -r 's@^@0x@;s@ @ 0x@g'))
if [[ "${#LP[*]}" == 8 ]]; then
  echo 'Found preexisting proxy entry.'
  LPL=$[0x64+0xff9b+${LP[0]}+${LP[1]}+${LP[2]}+${LP[3]}+${LP[4]}+${LP[5]}+${LP[6]}+${LP[7]}+65535+192*256+65535-1]
  echo "OLD: ${#LP[*]}: ${LP[*]} -> $[LPL%65535]"
  if [[ $[LP[0]] == "${L[0]}" ]]; then
    if [[ $[LP[1]] == "${L[1]}" ]]; then
      if [[ $[LP[2]] == "${L[2]}" ]]; then
        if [[ $[LP[3]] == "${L[3]}" ]]; then
          echo 'Reusing it.'
          L[4]=$[LP[4]]
          L[5]=$[LP[5]]
          L[6]=$[LP[6]]
          L[7]=$[LP[7]]
          PREEXIST=true
        fi
      fi
    fi
  fi
fi
echo "CHOSE: ${#L[*]}: [${L[*]}]"

if [[ "${PFX96}" != 64:ff9b:: ]]; then
  echo "Can only handle 64:ff9b::/96."
  exit
fi

declare -ra LOCAL6=("${L[@]}")
declare -ra LOCAL6HEX=$(printf "%x:" "${L[@]}")

echo -n "CUR: "; ip -6 neigh show proxy
echo "GEN: ${LOCAL6HEX%:} dev ${DEV} proxy"

if [[ $(id -u) != 0 ]]; then
  echo "Aborting, not root."
  exit 0
fi

if [[ "$*" == reset ]]; then
  echo 0 > /proc/sys/net/ipv6/conf/all/forwarding
  echo 0 > "/proc/sys/net/ipv6/conf/${DEV}/proxy_ndp"
fi
ip -6 neigh show proxy dev "${DEV}" | while read addr _; do
  ip -6 neigh del proxy "${addr}" dev "${DEV}"
done
#ip -6 neigh show dev "${DEV}" | egrep " lladdr ${MAC}" || :
ip -4 route del default metric 1 2>/dev/null || :
if [[ "$*" == reset ]]; then
  ip -4 addr del "${LOCAL4}/32" dev "${DEV}" 2>/dev/null || :
fi
tc qdisc del dev "${DEV}" clsact 2>/dev/null || :

if [[ "$*" == reset ]]; then
  exit 0
fi

sleep 1

echo 1 > "/proc/sys/net/ipv6/conf/${DEV}/proxy_ndp"
echo 1 > /proc/sys/net/ipv6/conf/all/forwarding
tc qdisc add dev "${DEV}" clsact
#tc filter add dev "${DEV}" ingress prio 1 protocol ipv6 u32 match ip6 src 64:ff9b::/96 match ip6 dst "${LOCAL6HEX%:}" action bpf object-file clatd.o section schedcls/clat_ingress_ether6
#tc filter add dev "${DEV}"  egress prio 1 protocol ip   u32 match ip src 192.0.0.1 action bpf object-file clatd.o section schedcls/clat_egress_ether4
tc filter add dev "${DEV}" ingress prio 1 protocol ipv6 bpf object-file clatd.o section schedcls/clat_ingress_ether6 direct-action
tc filter add dev "${DEV}"  egress prio 1 protocol ip   bpf object-file clatd.o section schedcls/clat_egress_ether4  direct-action

ip -4 addr add "${LOCAL4}/32" dev "${DEV}" || echo ip-addr-add-failed-but-ignoring

tc filter show dev "$DEV"
tc filter show dev "$DEV"  egress
tc filter show dev "$DEV" ingress

bpftool map show name clat_ifmac_map
bpftool map dump name clat_ifmac_map

bpftool map show name clat_srcip_map
bpftool map dump name clat_srcip_map

bpftool map show name clat_input_map
bpftool map dump name clat_input_map

bpftool map show name clat_output_map
bpftool map dump name clat_output_map

be32() {
  local u
  for u in "$@"; do
    local b0=$[(u >>  0) & 0xFF]
    local b1=$[(u >>  8) & 0xFF]
    local b2=$[(u >> 16) & 0xFF]
    local b3=$[(u >> 24) & 0xFF]
    printf "%d %d %d %d " "${b3}" "${b2}" "${b1}" "${b0}"
  done
}

le32() {
  local u
  for u in "$@"; do
    local b0=$[(u >>  0) & 0xFF]
    local b1=$[(u >>  8) & 0xFF]
    local b2=$[(u >> 16) & 0xFF]
    local b3=$[(u >> 24) & 0xFF]
    printf "%d %d %d %d " "${b0}" "${b1}" "${b2}" "${b3}"
  done
}

declare -r U32_IFINDEX=($(le32 "${IFINDEX}"))
declare -r U16_MTU4=($(u16 "${MTU4}"))
declare -r IP4_LOCAL=(${LOCAL4//./ })
declare -r IP6_LOCAL=($(be16 "${LOCAL6[@]}"))
declare -r IP6_PREFIX=(0 0x64 0xFF 0x9B 0 0 0 0 0 0 0 0 0 0 0 0)

declare -ra IF_KEY=("${U32_IFINDEX[@]}")
declare -ra IF_VALUE=($(tr ':' '\n' <<< "${MAC}" | while read i; do echo "0x${i}"; done))

declare -ra SRCIP_KEY=("${U32_IFINDEX[@]}" "${IP6_LOCAL[@]}")
declare -ra SRCIP_VALUE=("${IP6_PREFIX[@]}" "${IP4_LOCAL[@]}")

declare -ra OUT_KEY=("${U32_IFINDEX[@]}" "${IP4_LOCAL[@]}")
declare -ra OUT_VALUE=("${U32_IFINDEX[@]}" "${IP6_LOCAL[@]}" "${IP6_PREFIX[@]}" 1 0 "${U16_MTU4[@]}")

declare -ra IN_KEY=("${U32_IFINDEX[@]}" "${IP6_PREFIX[@]}" "${IP6_LOCAL[@]}")
declare -ra IN_VALUE=("${U32_IFINDEX[@]}" "${IP4_LOCAL[@]}")

#output: [u32 iif:IFINDEX, ip32 local4 ] -> [u32 oif:IFINDEX, ip128 local6, ip128 pfx96+zero24, bool isEthernet, u24 pad]
# input: [u32 iif:IFINDEX, ip128 pfx96+zero24, ip128 local6] -> [u32 oif:IFINDEX (or 0), ip32 local4]

bpftool map update name clat_ifmac_map key "${IF_KEY[@]}" value "${IF_VALUE[@]}" noexist
bpftool map lookup name clat_ifmac_map key "${IF_KEY[@]}"
bpftool map update name clat_srcip_map key "${SRCIP_KEY[@]}" value "${SRCIP_VALUE[@]}" noexist
bpftool map lookup name clat_srcip_map key "${SRCIP_KEY[@]}"
bpftool map update name clat_output_map key "${OUT_KEY[@]}" value "${OUT_VALUE[@]}" noexist
bpftool map lookup name clat_output_map key "${OUT_KEY[@]}"
bpftool map update name clat_input_map key "${IN_KEY[@]}" value "${IN_VALUE[@]}" noexist
bpftool map lookup name clat_input_map key "${IN_KEY[@]}"

ip -4 route add default via inet6 "${GW}" dev "${DEV}" mtu "${MTU4}" src "${LOCAL4}" metric 1

if ! "${PREEXIST}"; then
  echo "Triggering DAD for ${LOCAL6HEX%:}/128 on ${DEV}"
  ip -6 addr add "${LOCAL6HEX%:}/128" dev "${DEV}"
  sleep 1
  ip -6 addr del "${LOCAL6HEX%:}/128" dev "${DEV}"
fi

ip -6 neigh add proxy "${LOCAL6HEX%:}" dev "${DEV}"
{
  echo '#!/bin/bash'
  echo 'echo "Before: $(ip -6 neigh show proxy)"'
  echo "sudo ip -6 neigh add proxy '${LOCAL6HEX%:}' dev '${DEV}'"
  echo 'echo "After:  $(ip -6 neigh show proxy)"'
} > /tmp/neigh-proxy.sh
chmod a+x /tmp/neigh-proxy.sh
