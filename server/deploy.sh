#!/bin/bash
# Instaluje/aktualizuje mealquo-api z repozytorium do /opt/mealquo-api i restartuje usługę.
set -e
cd "$(dirname "$0")"
sudo install -d -o mealquo -g mealquo /opt/mealquo-api
sudo install -o root -g mealquo -m 644 server.mjs v2.mjs package.json package-lock.json /opt/mealquo-api/
sudo install -o root -g root -m 644 mealquo-api.service /etc/systemd/system/mealquo-api.service
sudo install -o root -g root -m 644 mealquo-db-backup /etc/cron.d/mealquo-db-backup
cd /opt/mealquo-api && sudo npm ci --omit=dev --silent && sudo chown -R root:mealquo node_modules
sudo systemctl daemon-reload
sudo systemctl enable --now mealquo-api
sudo systemctl restart mealquo-api
sleep 1
curl -s http://127.0.0.1:8790/health && echo
