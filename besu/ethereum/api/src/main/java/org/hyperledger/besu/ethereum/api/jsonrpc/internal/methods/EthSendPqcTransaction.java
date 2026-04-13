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

import org.hyperledger.besu.crypto.SECPSignature;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.api.jsonrpc.JsonRpcErrorConverter;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.exception.InvalidJsonRpcParameters;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.JsonRpcParameter.JsonRpcParameterException;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcError;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPool;
import org.hyperledger.besu.ethereum.mainnet.ValidationResult;
import org.hyperledger.besu.ethereum.transaction.TransactionInvalidReason;

import java.math.BigInteger;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import com.google.common.base.Suppliers;
import jakarta.validation.constraints.NotNull;
import org.apache.tuweni.bytes.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EthSendPqcTransaction implements JsonRpcMethod {
  private static final Logger LOG = LoggerFactory.getLogger(EthSendPqcTransaction.class);
  private static final SECPSignature DUMMY_SIGNATURE =
      SECPSignature.create(BigInteger.ONE, BigInteger.TWO, (byte) 0, BigInteger.valueOf(4));

  private final Supplier<TransactionPool> transactionPool;

  public EthSendPqcTransaction(final TransactionPool transactionPool) {
    this.transactionPool = Suppliers.ofInstance(transactionPool);
  }

  @Override
  public String getName() {
    return RpcMethod.ETH_SEND_PQC_TRANSACTION.getMethodName();
  }

  @Override
  public JsonRpcResponse response(final JsonRpcRequestContext requestContext) {
    final PqcTransactionParams params;
    try {
      params = requestContext.getRequiredParameter(0, PqcTransactionParams.class);
    } catch (JsonRpcParameterException e) {
      throw new InvalidJsonRpcParameters(
          "Invalid PQC transaction payload (index 0)", RpcErrorType.INVALID_TRANSACTION_PARAMS, e);
    }

    final Transaction transaction;
    try {
      transaction = buildTransaction(params);
      CompletableFuture.runAsync(
          () -> LOG.trace("Validated PQC transaction {} from {}", transaction.getHash(), transaction.getSender()));
    } catch (final IllegalArgumentException e) {
      LOG.debug("Invalid PQC transaction", e);
      return new JsonRpcErrorResponse(
          requestContext.getRequest().getId(),
          new JsonRpcError(RpcErrorType.INVALID_PARAMS.getCode(), e.getMessage(), null));
    }

    final ValidationResult<TransactionInvalidReason> validationResult =
        transactionPool.get().addTransactionViaApi(transaction);

    if (validationResult.isValid()) {
      PqcTransactionRegistry.recordSender(transaction.getHash(), transaction.getSender());
    }

    return validationResult.either(
        () -> new JsonRpcSuccessResponse(requestContext.getRequest().getId(), transaction.getHash().toString()),
        errorReason -> getJsonRpcResponse(requestContext, errorReason, validationResult));
  }

  private Transaction buildTransaction(final PqcTransactionParams params) {
    final Bytes publicKeyBytes = Bytes.fromHexString(PqcCrypto.normalizedHex(params.pqcPublicKey));
    final Bytes signatureBytes = Bytes.fromHexString(PqcCrypto.normalizedHex(params.pqcSignature));
    final Address derivedSender = PqcCrypto.deriveAddress(publicKeyBytes);

    if (params.from != null) {
      final Address claimedSender = Address.fromHexString(params.from);
      if (!claimedSender.equals(derivedSender)) {
        throw new IllegalArgumentException("from address does not match PQC public key");
      }
    }

    final Bytes message = PqcCrypto.canonicalMessage(params, derivedSender);
    if (!PqcCrypto.verify(message, publicKeyBytes, signatureBytes)) {
      throw new IllegalArgumentException("PQC signature verification failed");
    }

    return
        Transaction.builder()
            .nonce(PqcCrypto.quantityToLong(params.nonce))
            .gasPrice(Wei.of(PqcCrypto.quantityToBigInteger(params.gasPrice)))
            .gasLimit(PqcCrypto.quantityToLong(params.gasLimit))
            .to(params.to == null || params.to.isBlank() || params.to.equals("0x") ? null : Address.fromHexString(params.to))
            .value(Wei.of(PqcCrypto.quantityToBigInteger(params.value)))
            .payload(Bytes.fromHexString(PqcCrypto.normalizedHex(params.data)))
            .chainId(PqcCrypto.quantityToBigInteger(params.chainId))
            .sender(derivedSender)
            .signature(DUMMY_SIGNATURE)
            .build();
  }

  @NotNull
  private JsonRpcResponse getJsonRpcResponse(
      final JsonRpcRequestContext requestContext,
      final TransactionInvalidReason errorReason,
      final ValidationResult<TransactionInvalidReason> validationResult) {
    if (errorReason == TransactionInvalidReason.PLUGIN_TX_POOL_VALIDATOR) {
      final RpcErrorType rpcErrorType =
          JsonRpcErrorConverter.convertTransactionInvalidReason(
              validationResult.getInvalidReason());
      return new JsonRpcErrorResponse(
          requestContext.getRequest().getId(),
          new JsonRpcError(rpcErrorType.getCode(), validationResult.getErrorMessage(), null));
    }
    return new JsonRpcErrorResponse(
        requestContext.getRequest().getId(),
        JsonRpcErrorConverter.convertTransactionInvalidReason(errorReason));
  }
}
