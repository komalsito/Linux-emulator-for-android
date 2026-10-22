#!/bin/sh
# mkjail.sh - build a minimal chroot jail that runs the REAL emux-x11 /
# emux-apt-repair UNMODIFIED (all absolute paths resolve inside the jail).
# Used by run.sh to regression-test the desktop cascade against the field
# failure class: dpkg db says "install ok installed" while the package files
# are GONE ("ghost packages", v0.1.76/77/78 field rounds).
#
# The jail gets: dash, the coreutils the scripts shell out to, /dev/null,
# a stub dpkg (db file at /var/lib/dpkg/status) and a stub apt-get that
# models apt's REAL semantics: "install" of a package the db already lists
# as installed is a NO-OP (exactly what made ghost xvfb unfixable by
# apt-get install), --reinstall forces the unpack.
set -e
T="${JAILTEST_TMP:-/tmp/emux-jailtest}"
FR=$T/jail
rm -rf "$FR"
mkdir -p "$FR/bin" "$FR/usr/bin" "$FR/tmp" "$FR/etc" "$FR/var/lib/dpkg" \
         "$FR/usr/local/bin" "$FR/usr/local/lib/emux" "$FR/proc" "$FR/dev"
# some sandboxes lack mknod perms; a regular file is enough for the scripts
mknod "$FR/dev/null" c 1 3 2>/dev/null || { touch "$FR/dev/null"; chmod 666 "$FR/dev/null"; }

copybin() { # $1 host path - copy binary + its libs + interpreter into the jail
  [ -e "$1" ] || return 0
  d=$(dirname "$1")
  mkdir -p "$FR$d"
  cp -L "$1" "$FR$1"
  for lib in $(ldd "$1" 2>/dev/null | awk '{print $3}' | grep '^/'); do
    mkdir -p "$FR$(dirname "$lib")"
    [ -e "$FR$lib" ] || cp -L "$lib" "$FR$lib"
  done
  interp=$(ldd "$1" 2>/dev/null | awk '$1 ~ /^\/.*ld-linux/ {print $1; exit}')
  if [ -n "$interp" ]; then
    mkdir -p "$FR$(dirname "$interp")"
    [ -e "$FR$interp" ] || cp -L "$interp" "$FR$interp"
  fi
}

copybin /bin/sh
for b in /usr/bin/id /usr/bin/ln /usr/bin/stat /usr/bin/cat /usr/bin/mkdir \
         /usr/bin/rm /usr/bin/sleep /usr/bin/date /usr/bin/tr /usr/bin/grep \
         /usr/bin/chmod /usr/bin/tail /usr/bin/head /usr/bin/awk /usr/bin/wc \
         /usr/bin/mv /usr/bin/ls; do
  copybin "$b"
done

# fake dpkg db: coreutils/util-linux/bash HEALTHY (markers exist below);
# xvfb/xserver-common/x11-xkb-utils/xfonts-base are GHOSTS (db installed,
# files absent) - the exact post-aborted-transaction state from the field.
cat > "$FR/var/lib/dpkg/status" <<'EOF'
Package: coreutils
Status: install ok installed

Package: util-linux
Status: install ok installed

Package: bash
Status: install ok installed

Package: xvfb
Status: install ok installed

Package: xserver-common
Status: install ok installed

Package: x11-xkb-utils
Status: install ok installed

Package: xfonts-base
Status: install ok installed

EOF
: > "$FR/usr/bin/id"; : > "$FR/usr/bin/script"; : > "$FR/bin/bash"
chmod +x "$FR/usr/bin/id" "$FR/usr/bin/script" "$FR/bin/bash"

cat > "$FR/usr/bin/dpkg" <<'EOF'
#!/bin/sh
DB=/var/lib/dpkg/status
case "$1" in
  -s)
    pkg="$2"
    out=$(awk -v p="$pkg" '/^Package: /{cur=$2} /^Status: /{if(cur==p) print $0}' "$DB")
    [ -n "$out" ] && echo "$out"
    echo "$out" | grep -q "install ok installed" && exit 0 || exit 1 ;;
  --configure) exit 0 ;;
  --remove|--purge)
    shift; pkg=""
    while [ $# -gt 0 ]; do case "$1" in --force-all) ;; *) pkg="$1";; esac; shift; done
    awk -v p="$pkg" 'BEGIN{RS="";ORS="\n\n"} /^[ \t]*$/{next} {f=$0; sub("\n.*","",f); sub("^Package: ","",f); if(f!=p) print}' "$DB" > "$DB.n"
    mv "$DB.n" "$DB"
    echo "dpkg-stub: purged $pkg" >> /actions.log
    exit 0 ;;
  *) exit 0 ;;
esac
EOF
cat > "$FR/usr/bin/apt-get" <<'EOF'
#!/bin/sh
DB=/var/lib/dpkg/status
mode="$1"; shift
[ "$mode" = "update" ] && exit 0
reinstall=0; pkgs=""
for a in "$@"; do
  case "$a" in
    --reinstall) reinstall=1 ;;
    -y|-f|--no-install-recommends|--no-upgrade) ;;
    *) pkgs="$pkgs $a" ;;
  esac
done
[ "$mode" != "install" ] && exit 0
for p in $pkgs; do
  inst=$(awk -v p="$p" '/^Package: /{cur=$2} /^Status: /{if(cur==p) print $0}' "$DB" | grep -c "install ok installed")
  if [ "$inst" = "1" ] && [ "$reinstall" = "0" ]; then
    echo "apt-stub: no-op $p (db says installed)" >> /actions.log
    continue
  fi
  case "$p" in
    coreutils) : > /usr/bin/id; chmod +x /usr/bin/id ;;
    util-linux) : > /usr/bin/script; chmod +x /usr/bin/script ;;
    bash) : > /bin/bash; chmod +x /bin/bash ;;
    xvfb) printf '#!/bin/sh\necho "stub Xvfb up" > /tmp/xvfb.log\nmkdir -p /tmp/.X11-unix\n: > /tmp/.X11-unix/X0\nsleep 4\n' > /usr/bin/Xvfb; chmod +x /usr/bin/Xvfb ;;
    x11-xkb-utils) : > /usr/bin/xkbcomp; chmod +x /usr/bin/xkbcomp ;;
    xserver-common) mkdir -p /etc/X11; : > /etc/X11/Xsession ;;
    xfonts-base) mkdir -p /usr/share/fonts/X11/misc ;;
    x11-apps) : > /usr/bin/xwd; chmod +x /usr/bin/xwd ;;
    netpbm) : > /usr/bin/pnmtoplainpnm; chmod +x /usr/bin/pnmtoplainpnm ;;
    netcat-openbsd) : > /bin/nc.openbsd; chmod +x /bin/nc.openbsd ;;
    openbox) : > /usr/bin/openbox; chmod +x /usr/bin/openbox ;;
    xterm) : > /usr/bin/xterm; chmod +x /usr/bin/xterm ;;
  esac
  if [ "$inst" != "1" ]; then printf 'Package: %s\nStatus: install ok installed\n\n' "$p" >> "$DB"; fi
  echo "apt-stub: installed $p (reinstall=$reinstall)" >> /actions.log
done
exit 0
EOF
chmod +x "$FR/usr/bin/dpkg" "$FR/usr/bin/apt-get"
echo "JAIL_READY at $FR"
