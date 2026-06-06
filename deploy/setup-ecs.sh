#!/bin/bash
# ============================================================
# ECS Setup Script - Run once on the Alibaba Cloud ECS server
# to configure Nginx for APK download hosting and add SSH key
# ============================================================
set -euo pipefail

echo "=== DUIX Digital Human - ECS Setup ==="

# 1. Add GitHub Actions SSH public key
echo "Adding GitHub Actions SSH public key..."
mkdir -p ~/.ssh
chmod 700 ~/.ssh
touch ~/.ssh/authorized_keys
chmod 600 ~/.ssh/authorized_keys

# GitHub Actions deploy key for Duix-Mobile-SDK CI/CD
SSH_PUB_KEY="ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINujhENDy3I9qHa5YHGUw4zBIM3PGHE2WmNDaSrTAvtV github-actions-duix"

# Only add if not already present
if ! grep -qF "github-actions-duix" ~/.ssh/authorized_keys 2>/dev/null; then
    echo "$SSH_PUB_KEY" >> ~/.ssh/authorized_keys
    echo "SSH public key added."
else
    echo "SSH public key already exists, skipping."
fi

# 2. Install Nginx if not present
if ! command -v nginx &>/dev/null; then
    echo "Installing Nginx..."
    if command -v apt &>/dev/null; then
        apt update && apt install -y nginx
    elif command -v yum &>/dev/null; then
        yum install -y nginx
    fi
fi

# 3. Create download directory
mkdir -p /var/www/enlyai.com/downloads/duix
chown -R $(whoami) /var/www/enlyai.com/downloads/

# 4. Configure Nginx
cat > /etc/nginx/conf.d/download.conf << 'NGINX'
server {
    listen 80;
    server_name _;

    location /downloads/ {
        alias /var/www/enlyai.com/downloads/;
        autoindex on;
        autoindex_exact_size off;
        autoindex_localtime on;
        client_max_body_size 200M;
        add_header Access-Control-Allow-Origin *;
        add_header Content-Disposition 'attachment';
        expires 1h;
    }

    location = / {
        return 302 /downloads/duix/;
    }
}
NGINX

# 5. Test and reload Nginx
nginx -t && systemctl reload nginx || systemctl restart nginx
systemctl enable nginx

# 6. Open firewall
if command -v firewall-cmd &>/dev/null; then
    firewall-cmd --permanent --add-service=http
    firewall-cmd --reload
elif command -v ufw &>/dev/null; then
    ufw allow 80/tcp
fi

echo ""
echo "=== Setup Complete ==="
echo "SSH key added for GitHub Actions CI/CD deployment"
echo "Download URL: https://www.enlyai.com/downloads/duix/duix_digital_human.apk"
echo "Version API:  https://www.enlyai.com/downloads/duix/version.json"
echo "Upload directory: /var/www/enlyai.com/downloads/duix/"
