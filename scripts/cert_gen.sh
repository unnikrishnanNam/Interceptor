#!/bin/zsh

# Create certificates directory
CERT_DIR="./certs"
mkdir -p $CERT_DIR

echo "🔐 Generating SSL certificates for development..."

# Generate CA (Certificate Authority)
echo "1. Generating CA..."
openssl genrsa -out $CERT_DIR/ca.key 4096
openssl req -new -x509 -days 3650 -key $CERT_DIR/ca.key -out $CERT_DIR/ca.crt \
    -subj "/C=US/ST=State/L=City/O=Interceptor/OU=Dev/CN=Interceptor-CA"

# Generate Server Certificate for the Proxy
echo "2. Generating Server certificate..."
openssl genrsa -out $CERT_DIR/server.key 2048

# Create server certificate signing request
openssl req -new -key $CERT_DIR/server.key -out $CERT_DIR/server.csr \
    -subj "/C=US/ST=State/L=City/O=Interceptor/OU=Proxy/CN=localhost"

# Create extensions file for SAN (Subject Alternative Names)
cat > $CERT_DIR/server.ext << EOF
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:FALSE
keyUsage = digitalSignature, nonRepudiation, keyEncipherment, dataEncipherment
subjectAltName = @alt_names

[alt_names]
DNS.1 = localhost
DNS.2 = interceptor
DNS.3 = *.interceptor.local
IP.1 = 127.0.0.1
IP.2 = 0.0.0.0
EOF

# Sign server certificate with CA
openssl x509 -req -in $CERT_DIR/server.csr -CA $CERT_DIR/ca.crt -CAkey $CERT_DIR/ca.key \
    -CAcreateserial -out $CERT_DIR/server.crt -days 825 -sha256 -extfile $CERT_DIR/server.ext

# Generate Client Certificate (for mTLS/certificate-based auth)
echo "3. Generating Client certificate..."
openssl genrsa -out $CERT_DIR/client.key 2048
openssl req -new -key $CERT_DIR/client.key -out $CERT_DIR/client.csr \
    -subj "/C=US/ST=State/L=City/O=Interceptor/OU=Authority/CN=admin-authority"
openssl x509 -req -in $CERT_DIR/client.csr -CA $CERT_DIR/ca.crt -CAkey $CERT_DIR/ca.key \
    -CAcreateserial -out $CERT_DIR/client.crt -days 825 -sha256

# Create PKCS12 keystore for Spring Boot
echo "4. Creating PKCS12 keystore..."
openssl pkcs12 -export -in $CERT_DIR/server.crt -inkey $CERT_DIR/server.key \
    -out $CERT_DIR/server.p12 -name interceptor -CAfile $CERT_DIR/ca.crt \
    -caname root -password pass:changeit

# Create truststore with CA certificate
echo "5. Creating truststore..."
if keytool -list -keystore "$CERT_DIR/truststore.p12" \
   -storetype PKCS12 \
   -storepass changeit \
   -alias interceptor-ca >/dev/null 2>&1; then
  echo "Removing existing interceptor-ca from truststore..."
  keytool -delete \
    -alias interceptor-ca \
    -keystore "$CERT_DIR/truststore.p12" \
    -storetype PKCS12 \
    -storepass changeit
fi
keytool -import -trustcacerts -noprompt -alias interceptor-ca \
    -file $CERT_DIR/ca.crt -keystore $CERT_DIR/truststore.p12 \
    -storetype PKCS12 -storepass changeit

# Set permissions
chmod 600 $CERT_DIR/*.key
chmod 644 $CERT_DIR/*.crt $CERT_DIR/*.p12

echo ""
echo "✅ Certificates generated successfully in $CERT_DIR/"
echo ""
echo "Files created:"
ls -la $CERT_DIR/
echo ""

