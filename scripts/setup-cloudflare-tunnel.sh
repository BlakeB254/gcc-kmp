#!/usr/bin/env bash
# ============================================================
# Cloudflare Tunnel Setup for GCC API Server
# Exposes localhost:7310 as https://api.globalcommunitycleaners.org
# ============================================================
set -euo pipefail

echo "=== GCC API — Cloudflare Tunnel Setup ==="
echo ""

# ---- Step 1: Install cloudflared ----
if ! command -v cloudflared &>/dev/null; then
    echo "[1/5] Installing cloudflared..."
    # Debian/Ubuntu (DGX is Ubuntu-based)
    curl -fsSL https://pkg.cloudflare.com/cloudflare-main.gpg | sudo tee /usr/share/keyrings/cloudflare-main.gpg >/dev/null
    echo "deb [signed-by=/usr/share/keyrings/cloudflare-main.gpg] https://pkg.cloudflare.com/cloudflared $(lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/cloudflared.list
    sudo apt-get update && sudo apt-get install -y cloudflared
else
    echo "[1/5] cloudflared already installed: $(cloudflared --version)"
fi

# ---- Step 2: Authenticate ----
echo ""
echo "[2/5] Authenticating with Cloudflare..."
echo "       A browser window will open. Log in and select the domain."
echo "       If running headless, copy the URL from the terminal to your browser."
cloudflared tunnel login

# ---- Step 3: Create tunnel ----
TUNNEL_NAME="gcc-api"
echo ""
echo "[3/5] Creating tunnel '${TUNNEL_NAME}'..."
cloudflared tunnel create "${TUNNEL_NAME}" 2>/dev/null || echo "       Tunnel may already exist — continuing."

# Get tunnel ID
TUNNEL_ID=$(cloudflared tunnel list | grep "${TUNNEL_NAME}" | awk '{print $1}')
echo "       Tunnel ID: ${TUNNEL_ID}"

# ---- Step 4: Configure DNS ----
echo ""
echo "[4/5] Setting up DNS route..."
echo "       This creates a CNAME: api.globalcommunitycleaners.org -> ${TUNNEL_ID}.cfargotunnel.com"
cloudflared tunnel route dns "${TUNNEL_NAME}" api.globalcommunitycleaners.org 2>/dev/null || echo "       DNS route may already exist."

# ---- Step 5: Write config ----
CONFIG_DIR="${HOME}/.cloudflared"
CONFIG_FILE="${CONFIG_DIR}/config.yml"

echo ""
echo "[5/5] Writing tunnel config to ${CONFIG_FILE}..."

cat > "${CONFIG_FILE}" << EOF
tunnel: ${TUNNEL_ID}
credentials-file: ${CONFIG_DIR}/${TUNNEL_ID}.json

ingress:
  - hostname: api.globalcommunitycleaners.org
    service: http://localhost:7310
    originRequest:
      noTLSVerify: false
      connectTimeout: 30s
  - service: http_status:404
EOF

echo ""
echo "=== Setup Complete ==="
echo ""
echo "To run the tunnel:"
echo "  cloudflared tunnel run gcc-api"
echo ""
echo "To install as a systemd service (recommended):"
echo "  sudo cloudflared service install"
echo "  sudo systemctl enable cloudflared"
echo "  sudo systemctl start cloudflared"
echo ""
echo "Verify: curl https://api.globalcommunitycleaners.org/api/health"
