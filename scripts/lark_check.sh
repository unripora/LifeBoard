#!/system/bin/sh
LOG=$(ls -t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -1)
echo "log: $LOG"
echo "=== lark lines ==="
grep "com.ss.android.lark" "$LOG" 2>/dev/null | tail -15
echo ""
echo "=== lark count ==="
grep -c "com.ss.android.lark" "$LOG" 2>/dev/null
echo ""
echo "=== all app= packages seen ==="
grep -o 'app=[a-zA-Z0-9_.]*' "$LOG" 2>/dev/null | sort | uniq -c | sort -rn | head -20
