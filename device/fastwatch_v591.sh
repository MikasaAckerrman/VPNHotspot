#!/system/bin/sh
# vh_auto_watch.sh v5.9.1 (02.11) — Idempotent boot-fix.
# v5.9 был intent-authoritative, но имел PREV-лочischeskt STATE-глюк: после reboot
# если tun0 ещё не поднялся (VPN app ещё не успел), PREV записывался 'vpn', и когда
# tun0 появлялся позже — правило НЕ ставилось никогда (boot-resume не работал).
# v5.9.1: решение делается по (D, HAVE, TUNOK) — идempотентно к каждому тику,
# PREV и .vh_rule_state только для лога.

LOGF=/data/adb/wlan2_heal.log
INTF=/data/adb/.vh_intent
MODEF=/data/adb/wlan2_mode
IP=/system/bin/ip
IFACE=wlan2

log() { echo "$(date '+%m-%d %H:%M:%S') [fast] $*" >> "$LOGF"; }

for p in $(pgrep -f 'vh_auto_watch' 2>/dev/null); do
  [ "$p" != "$$" ] && kill "$p" 2>/dev/null
done
echo $$ > /data/adb/vh_auto_watch.pid
log "fastwatch v5.9.1 start pid=$$ (idempotent, intent-authoritative; boot-resume fix)"

desired() {
  M=$(cat "$MODEF" 2>/dev/null)
  case "$M" in
    vpn) echo vpn ;;
    novpn) echo novpn ;;
    *) IV=$(cat "$INTF" 2>/dev/null)
       case "$IV" in vpn) echo vpn ;; *) echo novpn ;; esac ;;
  esac
}

while :; do
  D=$(desired)
  HAVE=$($IP rule show 2>/dev/null | grep -c '^17500:')
  TUNOK=$(grep -q 'tun0:' /proc/net/dev 2>/dev/null && echo 1 || echo 0)
  PREV=$(cat /data/adb/.vh_rule_state 2>/dev/null)

  # Idempotent decisions: ничего не делаем, если уже верно (никаких флапов)
  if [ "$D" = "vpn" ]; then
    if [ "$TUNOK" = "0" ]; then
      [ "$PREV" = "vpn" ] || log "intent=vpn: tun0 отсутствует — ждём подъём VPN-tunnel"
    elif [ "$HAVE" -eq 0 ]; then
      $IP rule del pref 17500 2>/dev/null
      $IP rule add pref 17500 iif $IFACE lookup tun0 2>/dev/null \
        && log "intent=vpn: rule 17500 установлен"
    fi
  elif [ "$D" = "novpn" ] && [ "$HAVE" -ge 1 ]; then
    $IP rule del pref 17500 2>/dev/null \
      && log "intent=novpn: rule 17500 снят"
  fi
  [ "$D" != "$PREV" ] && echo "$D" > /data/adb/.vh_rule_state

  sleep 2
done
