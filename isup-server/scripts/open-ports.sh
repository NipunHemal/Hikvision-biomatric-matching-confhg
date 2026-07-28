#!/usr/bin/env bash
#
# Open the hub's inbound ports on the SERVER's host firewall.
#
# Run this ON THE SERVER (161.97.135.43), not in the container:
#     sudo bash isup-server/scripts/open-ports.sh
#
# Why this exists: docker-compose `ports:` only PUBLISHES ports from the container
# to the host — it cannot open the host OS firewall or the cloud provider's
# firewall. This script opens the host firewall (ufw / firewalld / iptables).
#
# NOTE: If you use a CLOUD PROVIDER firewall (Contabo / AWS SG / etc.), you must
# ALSO open these ports in that provider's web panel — no host command can do it.
set -e

TCP_PORTS=(7660 7663 8090)   # CMS register, alarm TCP, HTTP API
UDP_PORTS=(7662)             # alarm UDP

echo "== Opening hub ports on the host firewall =="

if command -v ufw >/dev/null 2>&1 && ufw status | grep -qi active; then
    echo "-> ufw detected"
    for p in "${TCP_PORTS[@]}"; do ufw allow "${p}/tcp"; done
    for p in "${UDP_PORTS[@]}"; do ufw allow "${p}/udp"; done
    ufw reload
    ufw status | grep -E '7660|7663|7662|8090' || true

elif command -v firewall-cmd >/dev/null 2>&1; then
    echo "-> firewalld detected"
    for p in "${TCP_PORTS[@]}"; do firewall-cmd --permanent --add-port="${p}/tcp"; done
    for p in "${UDP_PORTS[@]}"; do firewall-cmd --permanent --add-port="${p}/udp"; done
    firewall-cmd --reload

else
    echo "-> falling back to iptables"
    for p in "${TCP_PORTS[@]}"; do
        iptables -C INPUT -p tcp --dport "$p" -j ACCEPT 2>/dev/null \
            || iptables -I INPUT -p tcp --dport "$p" -j ACCEPT
    done
    for p in "${UDP_PORTS[@]}"; do
        iptables -C INPUT -p udp --dport "$p" -j ACCEPT 2>/dev/null \
            || iptables -I INPUT -p udp --dport "$p" -j ACCEPT
    done
    echo "   (iptables rules are not persistent across reboot unless you save them)"
fi

echo
echo "== Done. Verify the ports are LISTENING on the host =="
if command -v ss >/dev/null 2>&1; then
    ss -tulpn | grep -E '7660|7663|7662|8090' || echo "   (nothing listening yet — is the container running?)"
fi

cat <<'EOF'

Next: test reachability FROM OUTSIDE (run on your laptop, not the server):
    nc -vz 161.97.135.43 7663      # alarm TCP  -> must succeed
    nc -vz 161.97.135.43 7660      # CMS        -> must succeed

If 7660 succeeds but 7663 fails, the CLOUD PROVIDER firewall is blocking 7663 —
open it in the provider's panel (Contabo: Firewall / Networking).
EOF
