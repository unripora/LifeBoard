#!/system/bin/sh
LOG=$(ls -t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -1)
echo "log: $LOG"
echo "=== broadcast sent 次数 ==="
grep -c "broadcast sent" "$LOG" 2>/dev/null
echo ""
echo "=== 全部 broadcast sent ==="
grep "broadcast sent" "$LOG" 2>/dev/null
echo ""
echo "=== LifeBoardEntry 加载次数（initZygote in system） ==="
grep "initZygote" "$LOG" 2>/dev/null
echo ""
echo "=== hook 安装次数 ==="
grep "hooked" "$LOG" 2>/dev/null
