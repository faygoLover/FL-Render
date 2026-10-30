#!/usr/bin/env bash
# Галерея «Источники света» на хабе галерей (../../Gallery Hub, README там же).
#   выгрузка блоков из игры: мод Pack Audit/lightdump в копии инстанса (пишет lightdump.json на титуле и выходит)
#   python build.py                    — собрать site/ из lightdump.json и модов DEP_S5_rev
#   bash gallery_deploy.sh push        — выгрузить site/ (отметки не трогаются)
#   bash gallery_deploy.sh pull        — забрать отметки в review.json
#   bash gallery_deploy.sh link        — ссылки с ключом
set -euo pipefail
cd "$(dirname "$0")"
HUB="../../Gallery Hub/deploy.sh"
case "${1:-}" in
push) bash "$HUB" push lights "$PWD/site" ;;
pull) bash "$HUB" pull lights review "$PWD/review.json" ;;
link) bash "$HUB" link ;;
*) sed -n 2,7p "$0"; exit 1 ;;
esac
