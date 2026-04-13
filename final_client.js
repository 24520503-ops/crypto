const { execFileSync } = require("child_process");
const path = require("path");

const RPC_URL = process.env.RPC_URL || "http://127.0.0.1:8545";
const ROOT_DIR = __dirname;
const JAVA_HOME = process.env.JAVA_HOME || path.join(ROOT_DIR, ".local-jdk/jdk-21.0.10+7");
const JAVA_BIN = `${JAVA_HOME}/bin/java`;
const PQC_HELPER_CLASS_DIR = path.join(ROOT_DIR, ".build/pqc-signer");
const PQC_HELPER_MAIN_CLASS = "PqcSigner";
const BESU_LIB_GLOB = path.join(ROOT_DIR, "besu/build/install/besu/lib/*");
const DEFAULT_RECIPIENT = "0x1111111111111111111111111111111111111111";
const DEFAULT_VALUE_WEI = "0x1";
const DEFAULT_GAS_LIMIT = "0x5208";
const DEFAULT_GAS_PRICE = "0x0";
const DEFAULT_DATA = "0x";

async function rpc(method, params) {
  const response = await fetch(RPC_URL, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ jsonrpc: "2.0", id: 1, method, params }),
  });
  const json = await response.json();
  if (json.error) {
    throw new Error(`${json.error.code}: ${json.error.message}`);
  }
  return json.result;
}

function runSigner(argument) {
  const classpath = `${PQC_HELPER_CLASS_DIR}:${BESU_LIB_GLOB}`;
  const stdout = execFileSync(
    JAVA_BIN,
    [
      "-cp",
      classpath,
      PQC_HELPER_MAIN_CLASS,
      typeof argument === "string" ? argument : JSON.stringify(argument),
    ],
    { encoding: "utf8", cwd: ROOT_DIR }
  );
  return JSON.parse(stdout);
}

function hexToDecimalString(value) {
  return BigInt(value || "0x0").toString();
}

async function fetchChainState(address) {
  const [chainIdHex, blockNumberHex, latestNonceHex, pendingNonceHex, balanceHex] = await Promise.all([
    rpc("eth_chainId", []),
    rpc("eth_blockNumber", []),
    rpc("eth_getTransactionCount", [address, "latest"]),
    rpc("eth_getTransactionCount", [address, "pending"]),
    rpc("eth_getBalance", [address, "latest"]),
  ]);

  return {
    chainIdHex,
    blockNumberHex,
    latestNonceHex,
    pendingNonceHex,
    balanceHex,
  };
}

async function main() {
  const signer = runSigner("address");
  const sender = signer.address;
  const before = await fetchChainState(sender);

  const unsignedPayload = {
    to: process.env.PQC_TO || DEFAULT_RECIPIENT,
    value: process.env.PQC_VALUE || DEFAULT_VALUE_WEI,
    gasLimit: process.env.PQC_GAS_LIMIT || DEFAULT_GAS_LIMIT,
    gasPrice: process.env.PQC_GAS_PRICE || DEFAULT_GAS_PRICE,
    nonce: before.pendingNonceHex,
    chainId: before.chainIdHex,
    data: process.env.PQC_DATA || DEFAULT_DATA,
  };

  const signedPayload = runSigner(unsignedPayload);
  const txHash = await rpc("eth_sendPqcTransaction", [signedPayload]);
  const tx = await rpc("eth_getTransactionByHash", [txHash]);
  const afterPendingNonceHex = await rpc("eth_getTransactionCount", [sender, "pending"]);

  const response = {
    demo: "pqc-value-transfer",
    meaning: "A PQC-signed account transaction that attempts to transfer value to a non-zero recipient.",
    rpcUrl: RPC_URL,
    sender,
    recipient: unsignedPayload.to,
    chainId: hexToDecimalString(before.chainIdHex),
    blockBeforeSend: hexToDecimalString(before.blockNumberHex),
    latestNonceBeforeSend: hexToDecimalString(before.latestNonceHex),
    pendingNonceBeforeSend: hexToDecimalString(before.pendingNonceHex),
    balanceBeforeSendWei: hexToDecimalString(before.balanceHex),
    transaction: {
      hash: txHash,
      nonce: hexToDecimalString(unsignedPayload.nonce),
      valueWei: hexToDecimalString(unsignedPayload.value),
      gasLimit: hexToDecimalString(unsignedPayload.gasLimit),
      gasPriceWei: hexToDecimalString(unsignedPayload.gasPrice),
      data: unsignedPayload.data,
      from: signedPayload.from,
      to: signedPayload.to,
      chainIdHex: signedPayload.chainId,
      publicKeyPqc: signedPayload.pqcPublicKey,
      signaturePqc: signedPayload.pqcSignature,
      pqcSignatureBytes: Buffer.from(signedPayload.pqcSignature.replace(/^0x/, ""), "hex").length,
      pqcPublicKeyBytes: Buffer.from(signedPayload.pqcPublicKey.replace(/^0x/, ""), "hex").length,
    },
    acceptance: {
      nodeReturnedTxHash: Boolean(txHash),
      transactionVisibleByHash: tx !== null,
      blockNumberOnTransaction: tx?.blockNumber,
      pendingNonceAfterSend: hexToDecimalString(afterPendingNonceHex),
    },
    nextStep: "Run 'bash final_check.sh tx <txHash>' to see whether Besu has already included the transaction in a block.",
  };

  console.log(JSON.stringify(response, null, 2));
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
