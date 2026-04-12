#!/usr/bin/env sh
set -eu

GARAGE_ADMIN="http://garage:3903"
ADMIN_TOKEN="dev-admin-token"

echo "Waiting for Garage to start..."
until curl -s -o /dev/null -w "%{http_code}" "${GARAGE_ADMIN}/health" 2>/dev/null | grep -qE "^(200|503)$"; do
  sleep 1
done
echo "Garage is up."

# Get node ID
NODE_ID=$(curl -sf -H "Authorization: Bearer ${ADMIN_TOKEN}" "${GARAGE_ADMIN}/v2/GetClusterStatus" | jq -r '.nodes[] | select(.isUp == true) | .id' | head -1)
echo "Node ID: ${NODE_ID}"

# Check if layout already has this node assigned
STAGED=$(curl -sf -H "Authorization: Bearer ${ADMIN_TOKEN}" "${GARAGE_ADMIN}/v2/GetClusterLayout" | jq -r '.stagedRoleChanges // [] | length')
CURRENT=$(curl -sf -H "Authorization: Bearer ${ADMIN_TOKEN}" "${GARAGE_ADMIN}/v2/GetClusterLayout" | jq -r '[.roles[] | select(.id == "'"${NODE_ID}"'")] | length')

if [ "${CURRENT}" = "0" ]; then
  echo "Assigning layout..."
  curl -sf -X POST \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "{\"roles\": [{\"id\": \"${NODE_ID}\", \"zone\": \"dc1\", \"capacity\": 1073741824, \"tags\": [\"dev\"]}]}" \
    "${GARAGE_ADMIN}/v2/UpdateClusterLayout"

  # Get current layout version
  LAYOUT_VERSION=$(curl -sf -H "Authorization: Bearer ${ADMIN_TOKEN}" "${GARAGE_ADMIN}/v2/GetClusterLayout" | jq -r '.version')
  NEXT_VERSION=$((LAYOUT_VERSION + 1))

  echo "Applying layout version ${NEXT_VERSION}..."
  curl -sf -X POST \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "{\"version\": ${NEXT_VERSION}}" \
    "${GARAGE_ADMIN}/v2/ApplyClusterLayout"
  echo "Layout applied."
else
  echo "Layout already assigned, skipping."
fi

# Create a default access key if it doesn't exist
EXISTING_KEYS=$(curl -sf -H "Authorization: Bearer ${ADMIN_TOKEN}" "${GARAGE_ADMIN}/v2/ListKeys")
KEY_COUNT=$(echo "${EXISTING_KEYS}" | jq 'length')

if [ "${KEY_COUNT}" = "0" ]; then
  echo "Creating access key..."
  KEY_RESPONSE=$(curl -sf -X POST \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H "Content-Type: application/json" \
    -d '{"name": "dev-key"}' \
    "${GARAGE_ADMIN}/v2/CreateKey")

  ACCESS_KEY_ID=$(echo "${KEY_RESPONSE}" | jq -r '.accessKeyId')
  SECRET_ACCESS_KEY=$(echo "${KEY_RESPONSE}" | jq -r '.secretAccessKey')

  echo "============================================"
  echo "Access Key ID:     ${ACCESS_KEY_ID}"
  echo "Secret Access Key: ${SECRET_ACCESS_KEY}"
  echo "============================================"
  echo ""
  echo "Add these to your mise.toml:"
  echo "  GARAGE_S3_ACCESS_KEY_ID = \"${ACCESS_KEY_ID}\""
  echo "  GARAGE_S3_SECRET_ACCESS_KEY = \"${SECRET_ACCESS_KEY}\""
else
  echo "Access key already exists, skipping."
  ACCESS_KEY_ID=$(echo "${EXISTING_KEYS}" | jq -r '.[0].id')
fi

# Create a default bucket
EXISTING_BUCKETS=$(curl -sf -H "Authorization: Bearer ${ADMIN_TOKEN}" "${GARAGE_ADMIN}/v2/ListBuckets")
BUCKET_COUNT=$(echo "${EXISTING_BUCKETS}" | jq 'length')

if [ "${BUCKET_COUNT}" = "0" ]; then
  echo "Creating default bucket 'dev-bucket'..."
  BUCKET_RESPONSE=$(curl -sf -X POST \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H "Content-Type: application/json" \
    -d '{"globalAlias": "dev-bucket"}' \
    "${GARAGE_ADMIN}/v2/CreateBucket")

  BUCKET_ID=$(echo "${BUCKET_RESPONSE}" | jq -r '.id')

  # Grant permissions to the key
  curl -sf -X POST \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "{\"bucketId\": \"${BUCKET_ID}\", \"accessKeyId\": \"${ACCESS_KEY_ID}\", \"permissions\": {\"read\": true, \"write\": true, \"owner\": true}}" \
    "${GARAGE_ADMIN}/v2/AllowBucketKey"

  echo "Bucket 'dev-bucket' created and permissions granted."
else
  echo "Bucket already exists, skipping."
fi

echo "Garage initialization complete!"
