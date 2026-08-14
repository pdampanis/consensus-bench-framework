set +e
echo "== OS =="; . /etc/os-release; echo "$PRETTY_NAME"
IF=$(ip -o route get 10.0.0.99 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="dev") print $(i+1)}')
echo "== resolved iface: [$IF] =="
echo "--- ip -o route get raw ---"; ip -o route get 10.0.0.99

echo; echo "== TC: del on a CLEAN iface (no qdisc added) =="
tc qdisc del dev $IF root 2>&1; echo "exit=$?"

echo; echo "== TC: add netem, then del =="
tc qdisc add dev $IF root netem loss 30% 2>&1; echo "add exit=$?"
tc qdisc show dev $IF | head -2
tc qdisc del dev $IF root 2>&1; echo "del-after-add exit=$?"
tc qdisc del dev $IF root 2>&1; echo "del-again exit=$?"

echo; echo "== IPTABLES: -D when rule ABSENT =="
iptables -D INPUT -s 10.0.0.12 -j DROP 2>&1; echo "exit=$?"
echo "== IPTABLES: -A then -D =="
iptables -A INPUT -s 10.0.0.12 -j DROP; echo "add exit=$?"
iptables -D INPUT -s 10.0.0.12 -j DROP 2>&1; echo "del-after-add exit=$?"
iptables -D INPUT -s 10.0.0.12 -j DROP 2>&1; echo "del-again exit=$?"

echo; echo "== PKILL: no match =="
pkill -f 'stress-n[g] --cpu 2'; echo "exit=$?"
echo "== PKILL: with a match =="
nohup stress-ng --cpu 2 --timeout 60s >/dev/null 2>&1 & sleep 1
pkill -f 'stress-n[g] --cpu 2'; echo "exit=$?"
sleep 1; pkill -f 'stress-n[g] --cpu 2'; echo "exit-after-killed=$?"
