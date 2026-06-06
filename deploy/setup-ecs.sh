#!/bin/bash
# ============================================================
# ECS Setup Script - Run once on the Alibaba Cloud ECS server
# to configure Nginx for APK download hosting
# ============================================================
set -euo pipefail

echo "=== DUIX Digital Human - ECS Setup ==="

# Install Nginx if not present
if ! command -v nginx &>/dev/null; then
    echo "Installing Nginx..."
    if command -v apt &>/dev/null; then
        apt update && apt install -y nginx
    elif command -v yum &>/dev/null; then
        yum install -y nginx
    fi
fi

# Create download directory
mkdir -p /var/www/html/download/duix

# Configure Nginx
cat > /etc/nginx/conf.d/download.conf << 'NGINX'
server {
    listen 80;
    server_name _;

    # APK download endpoint
    location /download/ {
        alias /var/www/html/download/;
        autoindex on;
        autoindex_exact_size off;
        autoindex_localtime on;

        # Allow large file downloads
        client_max_body_size 200M;

        # CORS headers for APK downloads
        add_header Access-Control-Allow-Origin *;
        add_header Content-Disposition 'attachment';

        # Cache control
        expires 1h;
    }

    # Redirect root to download page
    location = / {
        return 302 /download/duix/;
    }
}
NGINX

# Test and reload Nginx
nginx -t && systemctl reload nginx || systemctl restart nginx

# Ensure Nginx starts on boot
systemctl enable nginx

# Open firewall
if command -v firewall-cmd &>/dev/null; then
    firewall-cmd --permanent --add-service=http
    firewall-cmd --reload
elif command -v ufw &>/dev/null; then
    ufw allow 80/tcp
fi

echo ""
echo "=== Setup Complete ==="
echo "Download URL: http://$(curl -s ifconfig.me)/download/duix/"
echo "Upload APKs to: /var/www/html/download/duix/"
