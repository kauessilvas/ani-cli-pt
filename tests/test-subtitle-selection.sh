#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
eval "$(sed -n '/^select_subtitle_link() {/,/^}/p' "$script_dir/ani-cli")"

assert_track() {
    _actual=$(select_subtitle_link "$1")
    if [ "$_actual" != "$2" ]; then
        printf 'expected: %s\nactual:   %s\n' "$2" "$_actual" >&2
        exit 1
    fi
}

_ptbr='{"subtitles":[{"lang":"en","label":"English","src":"https://cdn.example/eng-1.vtt","default":true},{"lang":"en","label":"Portuguese (Brazil)","src":"https://cdn.example/por-br.vtt"}]}'
_ptbr_priority='{"subtitles":[{"label":"Portuguese","src":"https://cdn.example/por-1.vtt"},{"label":"Portuguese (Brazil)","src":"https://cdn.example/por-br.vtt"}]}'
_portuguese='{"subtitles":[{"lang":"en","label":"English","src":"https://cdn.example/eng-1.vtt","default":true},{"lang":"en","label":"Portuguese","src":"https://cdn.example/por-2.vtt"}]}'
_english='{"subtitles":[{"lang":"en","label":"English","src":"https://cdn.example/eng-1.vtt"},{"lang":"en","label":"English (Crunchyroll)","src":"https://cdn.example/eng-2.vtt","default":true}]}'
_default='{"subtitles":[{"lang":"en","src":"https://cdn.example/eng-default.vtt","default":true}]}'

assert_track "$_ptbr" 'https://cdn.example/por-br.vtt'
assert_track "$_ptbr_priority" 'https://cdn.example/por-br.vtt'
assert_track "$_portuguese" 'https://cdn.example/por-2.vtt'
assert_track "$_english" 'https://cdn.example/eng-1.vtt'
assert_track "$_default" 'https://cdn.example/eng-default.vtt'
assert_track '{"src":"https://cdn.example/video.m3u8"}' ''

printf 'Subtitle selection tests passed.\n'
