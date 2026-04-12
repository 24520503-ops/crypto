/*
 * Copyright contributors to Hyperledger Besu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods;

import static org.hyperledger.besu.crypto.Hash.keccak256;
import static org.hyperledger.besu.crypto.Hash.sha256;

import org.hyperledger.besu.datatypes.Address;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Security;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Locale;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.pqc.jcajce.provider.BouncyCastlePQCProvider;

final class PqcCrypto {
  static final String PQC_ALGORITHM = "DILITHIUM5";
  static final String PQC_SIGNATURE_ALGORITHM = "DILITHIUM";

  static {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    if (Security.getProvider(BouncyCastlePQCProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastlePQCProvider());
    }
  }

  private PqcCrypto() {}

  static Address deriveAddress(final Bytes encodedPublicKey) {
    final Bytes32 hash = keccak256(encodedPublicKey);
    return Address.extract(hash);
  }

  static boolean verify(final Bytes message, final Bytes encodedPublicKey, final Bytes signature) {
    try {
      final KeyFactory keyFactory =
          KeyFactory.getInstance(PQC_ALGORITHM, BouncyCastlePQCProvider.PROVIDER_NAME);
      final PublicKey publicKey =
          keyFactory.generatePublic(new X509EncodedKeySpec(encodedPublicKey.toArrayUnsafe()));
      final Signature verifier =
          Signature.getInstance(PQC_SIGNATURE_ALGORITHM, BouncyCastlePQCProvider.PROVIDER_NAME);
      verifier.initVerify(publicKey);
      verifier.update(message.toArrayUnsafe());
      return verifier.verify(signature.toArrayUnsafe());
    } catch (final Exception e) {
      throw new IllegalArgumentException("Unable to verify PQC signature", e);
    }
  }

  static Bytes canonicalMessage(final PqcTransactionParams params, final Address derivedSender) {
    final String payload =
        String.join(
            "|",
            normalizedHex(derivedSender.toHexString()),
            normalizedHex(params.to),
            normalizedQuantity(params.value),
            normalizedQuantity(params.gasLimit),
            normalizedQuantity(params.gasPrice),
            normalizedQuantity(params.nonce),
            normalizedQuantity(params.chainId),
            normalizedHex(params.data));
    return sha256(Bytes.wrap(payload.getBytes(StandardCharsets.UTF_8)));
  }

  static BigInteger quantityToBigInteger(final String value) {
    return new BigInteger(stripHexPrefix(defaultQuantity(value)), 16);
  }

  static long quantityToLong(final String value) {
    return quantityToBigInteger(value).longValueExact();
  }

  static String normalizedQuantity(final String value) {
    return "0x" + quantityToBigInteger(value).toString(16);
  }

  static String normalizedHex(final String value) {
    if (value == null || value.isBlank() || value.equals("0x")) {
      return "0x";
    }
    final String stripped = stripHexPrefix(value).toLowerCase(Locale.ROOT);
    return "0x" + (stripped.length() % 2 == 0 ? stripped : "0" + stripped);
  }

  private static String defaultQuantity(final String value) {
    return value == null || value.isBlank() ? "0x0" : value;
  }

  private static String stripHexPrefix(final String value) {
    return value.startsWith("0x") || value.startsWith("0X") ? value.substring(2) : value;
  }
}
