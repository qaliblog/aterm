set -e  # Exit immediately on Failure

export PATH=/bin:/sbin:/usr/bin:/usr/sbin:/usr/share/bin:/usr/share/sbin:/usr/local/bin:/usr/local/sbin:/system/bin:/system/xbin
export HOME=/root

if [ ! -s /etc/resolv.conf ]; then
    echo "nameserver 8.8.8.8" > /etc/resolv.conf
fi

export PS1="\[\e[38;5;46m\]\u\[\033[39m\]@reterm \[\033[39m\]\w \[\033[0m\]\\$ "
# shellcheck disable=SC2034
export PIP_BREAK_SYSTEM_PACKAGES=1
export DEBIAN_FRONTEND=noninteractive

# Update package lists and upgrade system
if [ ! -f /var/lib/apt/lists/lock ]; then
    echo -e "\e[34;1m[*] \e[0mUpdating package lists\e[0m"
    apt-get update -qq || true
fi

# Check and install essential packages
required_packages="bash nano curl wget"
missing_packages=""
for pkg in $required_packages; do
    if ! dpkg -l | grep -q "^ii.*$pkg "; then
        missing_packages="$missing_packages $pkg"
    fi
done

if [ -n "$missing_packages" ]; then
    echo -e "\e[34;1m[*] \e[0mInstalling Important packages\e[0m"
    apt-get update -qq
    apt-get upgrade -y -qq || true
    apt-get install -y -qq $missing_packages
    if [ $? -eq 0 ]; then
        echo -e "\e[32;1m[+] \e[0mSuccessfully Installed\e[0m"
    fi
    echo -e "\e[34m[*] \e[0mUse \e[32mapt\e[0m to install new packages\e[0m"
fi

# Install fish shell if not already installed
if ! command -v fish >/dev/null 2>&1; then
    echo -e "\e[34;1m[*] \e[0mInstalling fish shell\e[0m"
    apt-get update -qq
    apt-get install -y -qq fish 2>/dev/null || true
    if command -v fish >/dev/null 2>&1; then
        echo -e "\e[32;1m[+] \e[0mFish shell installed\e[0m"
    fi
fi

# Install cron if not already installed
if ! command -v cron >/dev/null 2>&1; then
    apt-get install -y -qq cron 2>/dev/null || true
fi

# Copy fish color update script
if [ -f "$PREFIX/local/bin/update-fish-colors.sh" ]; then
    # Script already exists, just make it executable
    chmod +x "$PREFIX/local/bin/update-fish-colors.sh" 2>/dev/null || true
else
    # Create the script
    mkdir -p "$PREFIX/local/bin" 2>/dev/null || true
    cat > "$PREFIX/local/bin/update-fish-colors.sh" << 'SCRIPTEOF'
#!/bin/sh
# Script to update fish shell colors based on app theme
# This script reads the Android app's SharedPreferences to detect theme

# Paths to check for SharedPreferences
PREF_PATHS="/data/data/com.qali.aterm/shared_prefs/Settings.xml /data/data/com.qali.aterm.debug/shared_prefs/Settings.xml"

# Default to dark theme if we can't detect
IS_DARK_MODE=1

# Try to read the theme setting from SharedPreferences
for PREF_PATH in $PREF_PATHS; do
    if [ -f "$PREF_PATH" ]; then
        # Read default_night_mode value (MODE_NIGHT_YES=2, MODE_NIGHT_NO=1, MODE_NIGHT_FOLLOW_SYSTEM=0)
        NIGHT_MODE=$(grep -o 'name="default_night_mode"[^>]*>\([0-9]*\)</int>' "$PREF_PATH" 2>/dev/null | grep -o '[0-9]*' | tail -1)
        if [ -n "$NIGHT_MODE" ]; then
            # MODE_NIGHT_YES = 2 (dark), MODE_NIGHT_NO = 1 (light), MODE_NIGHT_FOLLOW_SYSTEM = 0
            if [ "$NIGHT_MODE" = "2" ]; then
                IS_DARK_MODE=1
            elif [ "$NIGHT_MODE" = "1" ]; then
                IS_DARK_MODE=0
            else
                # MODE_NIGHT_FOLLOW_SYSTEM - try to detect system theme
                IS_DARK_MODE=1  # Default to dark
            fi
            break
        fi
    fi
done

# Create fish config directory if it doesn't exist
mkdir -p ~/.config/fish 2>/dev/null || true

# Update fish colors based on theme
if [ "$IS_DARK_MODE" = "1" ]; then
    # Dark theme: Use light colors
    cat > ~/.config/fish/config.fish << 'FISHDARK'
# Fish shell configuration for dark theme (light colors)
set -g fish_color_normal white
set -g fish_color_command cyan
set -g fish_color_quote yellow
set -g fish_color_redirection magenta
set -g fish_color_end green
set -g fish_color_error red
set -g fish_color_param white
set -g fish_color_comment brblack
set -g fish_color_match --background=brblue
set -g fish_color_selection white --bold --background=brblack
set -g fish_color_search_match bryellow --background=brblack
set -g fish_color_history_current --bold
set -g fish_color_operator brcyan
set -g fish_color_escape brcyan
set -g fish_color_cwd green
set -g fish_color_cwd_root red
set -g fish_color_valid_path --underline
set -g fish_color_autosuggestion brblack
set -g fish_color_user brgreen
set -g fish_color_host normal
set -g fish_color_cancel -r
set -g fish_pager_color_completion normal
set -g fish_pager_color_description B3a06a yellow
set -g fish_pager_color_prefix white --bold --underline
set -g fish_pager_color_progress brwhite --background=cyan
FISHDARK
else
    # Light theme: Use dark colors
    cat > ~/.config/fish/config.fish << 'FISHLIGHT'
# Fish shell configuration for light theme (dark colors)
set -g fish_color_normal black
set -g fish_color_command blue
set -g fish_color_quote yellow
set -g fish_color_redirection magenta
set -g fish_color_end green
set -g fish_color_error red
set -g fish_color_param black
set -g fish_color_comment brblack
set -g fish_color_match --background=brblue
set -g fish_color_selection black --bold --background=brwhite
set -g fish_color_search_match bryellow --background=brwhite
set -g fish_color_history_current --bold
set -g fish_color_operator brcyan
set -g fish_color_escape brcyan
set -g fish_color_cwd green
set -g fish_color_cwd_root red
set -g fish_color_valid_path --underline
set -g fish_color_autosuggestion brblack
set -g fish_color_user brgreen
set -g fish_color_host normal
set -g fish_color_cancel -r
set -g fish_pager_color_completion normal
set -g fish_pager_color_description B3a06a yellow
set -g fish_pager_color_prefix black --bold --underline
set -g fish_pager_color_progress brblack --background=cyan
FISHLIGHT
fi

# Make script executable
chmod +x ~/.config/fish/config.fish 2>/dev/null || true
SCRIPTEOF
    chmod +x "$PREFIX/local/bin/update-fish-colors.sh" 2>/dev/null || true
fi

# Run the script once to set initial colors
"$PREFIX/local/bin/update-fish-colors.sh" 2>/dev/null || true

# Add to crontab to run every minute (checks for theme changes)
(crontab -l 2>/dev/null | grep -v "update-fish-colors.sh"; echo "* * * * * $PREFIX/local/bin/update-fish-colors.sh >/dev/null 2>&1") | crontab - 2>/dev/null || true

# Start cron daemon if not running
if ! pgrep -x cron >/dev/null 2>&1; then
    # Try multiple methods to start cron
    if command -v service >/dev/null 2>&1; then
        service cron start 2>/dev/null || true
    elif [ -f /etc/init.d/cron ]; then
        /etc/init.d/cron start 2>/dev/null || true
    elif [ -f /usr/sbin/cron ]; then
        /usr/sbin/cron 2>/dev/null || true
    fi
    # Wait a moment to ensure it started
    sleep 1
    # Verify it's running
    if pgrep -x cron >/dev/null 2>&1; then
        echo -e "\e[32;1m[+] \e[0mCron daemon started\e[0m"
    else
        echo -e "\e[33;1m[!] \e[0mWarning: Failed to start cron daemon (this is normal in some environments)\e[0m"
    fi
fi

#fix linker warning
if [[ ! -f /linkerconfig/ld.config.txt ]];then
    mkdir -p /linkerconfig
    touch /linkerconfig/ld.config.txt
fi

# Fix group warnings by adding missing group entries or suppressing warnings
# The groups command may show warnings for group IDs that don't have names in /etc/group
# This is harmless but we can suppress it by ensuring /etc/group has entries or redirecting stderr
if [ -f /etc/group ]; then
    # Add common missing group IDs as dummy entries if they don't exist
    for gid in 3003 9997 20609 50609 99909997; do
        if ! grep -q "^[^:]*:[^:]*:$gid:" /etc/group 2>/dev/null; then
            echo "android_$gid:x:$gid:" >> /etc/group 2>/dev/null || true
        fi
    done
fi

if [ "$#" -eq 0 ]; then
    source /etc/profile 2>/dev/null || true
    export PS1="\[\e[38;5;46m\]\u\[\033[39m\]@reterm \[\033[39m\]\w \[\033[0m\]\\$ "
    cd $HOME
    /bin/bash
else
    exec "$@"
fi

