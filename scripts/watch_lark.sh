#!/system/bin/sh
LOG=$(ls -t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -1)
echo "log: $(basename $LOG)"
echo "=== 所有飞书通知（含内容和匹配结果） ==="
grep "app=com.ss.android.lark" "$LOG" 2>/dev/null | grep -v "skip duplicate" | tail -20
echo ""
echo "=== 含「打卡」的通知 ==="
grep "打卡" "$LOG" 2>/dev/null | tail -10
