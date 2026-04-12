import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Security;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.pqc.jcajce.provider.BouncyCastlePQCProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

public class PqcSigner {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String PQC_PROVIDER = BouncyCastlePQCProvider.PROVIDER_NAME;
  private static final String PQC_ALGORITHM = "DILITHIUM5";
  private static final String PQC_SIGNATURE_ALGORITHM = "DILITHIUM";
  private static final Path WALLET_DIR =
      Paths.get(System.getenv().getOrDefault("PQC_WALLET_DIR", ".pqc-wallet"));
  private static final Path PRIVATE_KEY_PATH = WALLET_DIR.resolve("dilithium-private.pk8");
  private static final Path PUBLIC_KEY_PATH = WALLET_DIR.resolve("dilithium-public.spki");

  public static void main(final String[] args) throws Exception {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    if (Security.getProvider(BouncyCastlePQCProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastlePQCProvider());
    }

    if (args.length == 0) {
      throw new IllegalArgumentException("Expected 'address' or a JSON payload argument");
    }

    final KeyPair keyPair = loadOrCreateKeyPair();

    if ("address".equals(args[0])) {
      final AddressOnly addressOnly = new AddressOnly();
      addressOnly.address = deriveAddress(keyPair.getPublic().getEncoded());
      System.out.println(MAPPER.writeValueAsString(addressOnly));
      return;
    }

    final Payload payload = MAPPER.readValue(args[0], Payload.class);

    payload.from = deriveAddress(keyPair.getPublic().getEncoded());

    final Signature signer = Signature.getInstance(PQC_SIGNATURE_ALGORITHM, PQC_PROVIDER);
    final KeyFactory keyFactory = KeyFactory.getInstance(PQC_ALGORITHM, PQC_PROVIDER);

    signer.initSign(
        keyFactory.generatePrivate(new PKCS8EncodedKeySpec(keyPair.getPrivate().getEncoded())));
    signer.update(canonicalMessage(payload));
    payload.pqcPublicKey = "0x" + toHex(keyPair.getPublic().getEncoded());
    payload.pqcSignature = "0x" + toHex(signer.sign());

    System.out.println(MAPPER.writeValueAsString(payload));
  }

  private static KeyPair loadOrCreateKeyPair() throws Exception {
    final KeyFactory keyFactory = KeyFactory.getInstance(PQC_ALGORITHM, PQC_PROVIDER);

    if (Files.exists(PRIVATE_KEY_PATH) && Files.exists(PUBLIC_KEY_PATH)) {
      return new KeyPair(
          keyFactory.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(PUBLIC_KEY_PATH))),
          keyFactory.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(PRIVATE_KEY_PATH))));
    }

    Files.createDirectories(WALLET_DIR);
    final KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(PQC_ALGORITHM, PQC_PROVIDER);
    final KeyPair keyPair = keyPairGenerator.generateKeyPair();
    Files.write(PRIVATE_KEY_PATH, keyPair.getPrivate().getEncoded());
    Files.write(PUBLIC_KEY_PATH, keyPair.getPublic().getEncoded());
    return keyPair;
  }

  private static byte[] canonicalMessage(final Payload payload) throws Exception {
    final String canonical =
        String.join(
            "|",
            normalizeHex(payload.from),
            normalizeHex(payload.to),
            normalizeQuantity(payload.value),
            normalizeQuantity(payload.gasLimit),
            normalizeQuantity(payload.gasPrice),
            normalizeQuantity(payload.nonce),
            normalizeQuantity(payload.chainId),
            normalizeHex(payload.data));
    return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
  }

  private static String deriveAddress(final byte[] publicKey) throws Exception {
    final byte[] hash = MessageDigest.getInstance("KECCAK-256", BouncyCastleProvider.PROVIDER_NAME)
        .digest(publicKey);
    final byte[] address = new byte[20];
    System.arraycopy(hash, hash.length - 20, address, 0, 20);
    return "0x" + toHex(address);
  }

  private static String normalizeHex(final String value) {
    if (value == null || value.isBlank() || value.equals("0x")) {
      return "0x";
    }
    final String stripped = stripHexPrefix(value).toLowerCase();
    return "0x" + (stripped.length() % 2 == 0 ? stripped : "0" + stripped);
  }

  private static String normalizeQuantity(final String value) {
    final String quantity = value == null || value.isBlank() ? "0x0" : value;
    return "0x" + new BigInteger(stripHexPrefix(quantity), 16).toString(16);
  }

  private static String stripHexPrefix(final String value) {
    if (value.startsWith("0x") || value.startsWith("0X")) {
      return value.substring(2);
    }
    return value;
  }

  private static String toHex(final byte[] bytes) {
    final StringBuilder builder = new StringBuilder(bytes.length * 2);
    for (final byte value : bytes) {
      builder.append(String.format("%02x", value));
    }
    return builder.toString();
  }

  public static class Payload {
    public String from;
    public String to;
    public String value;
    public String gasLimit;
    public String gasPrice;
    public String nonce;
    public String chainId;
    public String data;
    public String pqcPublicKey;
    public String pqcSignature;
  }

  public static class AddressOnly {
    public String address;
  }
}
