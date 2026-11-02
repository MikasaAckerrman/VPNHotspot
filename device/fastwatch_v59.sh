#!/system/bin/sh
# vh_auto_watch.sh v5.9 (02.11) — ЯДРО-синхронизатор по INTENT-ФАЙЛУ (authoritative).
#
# ROOT-CAUSE УСТРАНЁН (заменённая версия v4.42-v4.9 имела case S1/S2 по iptables-
# REJECT-подписи («S2 → setvpn») — app'ы stale-цепочки после RAM-kill давали
# ложный S2 ⇒ правило «само включается» при выключенном тумблере юзера).
#
# НОВАЯ семантика (одна истина — intent-файл /data/adb/.vh_intent):
#   intent=vpn    → правило 17500 iif wlan2 lookup tun0 должно стоять
#   intent=novpn  → правило должно быть снято
#   MODE=vpn|novpn → ручной форс (перекрывает intent); auto|пусто → follow intent.
# Писатели intent-файла (все легитимные):
#   1) приложение (v-vh4+: userTap → su echo)    2) чат-команды vh_usercmd.sh
# Всё остальное (старые REJ-цепочки приложения и т.п.) больше НЕ влияет на ядро.
# idempotency: если rule уже как требуется — нет действий (никаких флапов).

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
log "fastwatch v5.9 start pid=$$ (intent-authoritative; stale-REJ edge УБРАН)"

desired() { # печатает vpn|novpn — что должно быть с правилом
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

  if [ "$D" = "vpn" ] && [ "$PREV" != "vpn" ] && [ "$TUNOK" = "1" ]; then
    $IP rule del pref 17500 2>/dev/null
    $IP rule add pref 17500 iif $IFACE lookup tun0 2>/dev/null \
      && log "intent=vpn: rule 17500 установлен"
  elif [ "$D" = "novpn" ] && [ "$HAVE" -ge 1 ]; then
    $IP rule del pref 17500 2>/dev/null \
      && log "intent=novpn: rule 17500 снят"
  fi
  # держим маркер (нужен для «SetValue once» → без флапов)
  if [ "$D" != "$PREV" ]; then echo "$D" > /data/adb/.vh_rule_state; fi

  sleep 2
done
