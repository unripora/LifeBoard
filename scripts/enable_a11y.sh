#!/system/bin/sh
CUR=$(settings get secure enabled_accessibility_services)
TARGET="com.lifeboard.app/com.lifeboard.server.notice.ScreenCaptureAccessibilityService"
case "$CUR" in
  *"$TARGET"*) echo "already enabled" ;;
  *)
    if [ -z "$CUR" ] || [ "$CUR" = "null" ]; then
      NEW="$TARGET"
    else
      NEW="$CUR:$TARGET"
    fi
    settings put secure enabled_accessibility_services "$NEW"
    settings put secure accessibility_enabled 1
    echo "enabled"
    ;;
esac
sleep 1
settings get secure enabled_accessibility_services
