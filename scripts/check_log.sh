#!/system/bin/sh
LOG=$(ls -t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -1)
VLOG=$(ls -t /data/adb/lspd/log/verbose_*.log 2>/dev/null | head -1)
echo "modules log: $LOG"
echo "verbose log: $VLOG"
echo "=== lifeboard occurrences in modules log ==="
grep -c -i lifeboard "$LOG" 2>/dev/null
echo "=== lifeboard lines (max 30) ==="
grep -i lifeboard "$LOG" 2>/dev/null | head -30
echo "=== modules loaded into system in modules log ==="
grep -o '(system)\[[^,]*' "$LOG" 2>/dev/null | sort -u
echo "=== lifeboard occurrences in verbose log ==="
grep -c -i lifeboard "$VLOG" 2>/dev/null
