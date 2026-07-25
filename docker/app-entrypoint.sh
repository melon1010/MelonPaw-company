#!/bin/sh
set -eu

data_dir="${MELON_HOME:-/data}"
workspace_dir="${MELON_WORKSPACE_ROOT:-${data_dir}/workspaces}"

mkdir -p "$data_dir" "$workspace_dir"
chown melon:melon "$data_dir" "$workspace_dir"

if test -S /var/run/docker.sock; then
  socket_gid="$(stat -c '%g' /var/run/docker.sock)"
  socket_group="$(getent group "$socket_gid" | cut -d: -f1 || true)"
  if test -z "$socket_group"; then
    socket_group="docker-host"
    groupadd --gid "$socket_gid" "$socket_group"
  fi
  usermod -aG "$socket_group" melon
fi

exec /usr/bin/tini -- runuser -u melon -- "$@"

