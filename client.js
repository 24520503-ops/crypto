const { execFileSync } = require("child_process");
const path = require("path");

const RPC_URL = process.env.RPC_URL || "http://127.0.0.1:8545";
const ROOT_DIR = __dirname;
const JAVA_HOME = process.env.JAVA_HOME || path.join(ROOT_DIR, ".local-jdk/jdk-21.0.10+7");
const JAVA_BIN = `${JAVA_HOME}/bin/java`;
const PQC_HELPER_CLASS_DIR = path.join(ROOT_DIR, ".build/pqc-signer");
const PQC_HELPER_MAIN_CLASS = "PqcSigner";
const BESU_LIB_GLOB = path.join(ROOT_DIR, "besu/build/install/besu/lib/*");
const DEFAULT_RECIPIENT = "0x0000000000000000000000000000000000000000";

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

async function nextNonce(address) {
  return rpc("eth_getTransactionCount", [address, "pending"]);
}

function hexQuantityToString(value) {
  return BigInt(value || "0x0").toString();
}

function buildTxSummary(txHash, signedPayload) {
  const fee = BigInt(signedPayload.gasLimit || "0x0") * BigInt(signedPayload.gasPrice || "0x0");

  return {
    txid: txHash.replace(/^0x/, ""),
    size: Buffer.byteLength(JSON.stringify(signedPayload)),
    version: 1,
    locktime: 0,
    fee: fee.toString(),
    nonce: hexQuantityToString(signedPayload.nonce),
    gasLimit: hexQuantityToString(signedPayload.gasLimit),
    gasPrice: hexQuantityToString(signedPayload.gasPrice),
    inputs: [
      {
        coinbase: false,
        txid: null,
        output: null,
        sigscript: signedPayload.pqcSignature,
        sequence: Number(BigInt(signedPayload.nonce || "0x0")),
        pkscript: signedPayload.pqcPublicKey,
        value: null,
        address: signedPayload.from,
        witness: [],
      },
    ],
    outputs: [
      {
        address: signedPayload.to,
        pkscript: signedPayload.data === "0x" ? null : signedPayload.data,
        value: hexQuantityToString(signedPayload.value),
        spent: false,
        spender: null,
      },
    ],
  };
}

async function main() {
  const [chainIdHex, blockNumberHex] = await Promise.all([
    rpc("eth_chainId", []),
    rpc("eth_blockNumber", []),
  ]);

  const { address } = runSigner("address");
  const nonceHex = await nextNonce(address);

  const unsignedPayload = {
    to: process.env.PQC_TO || DEFAULT_RECIPIENT,
    value: "0x0",
    gasLimit: "0x5208",
    gasPrice: "0x0",
    nonce: nonceHex,
    chainId: chainIdHex,
    data: "0x",
  };

  const signedPayload = runSigner(unsignedPayload);
  const txHash = await rpc("eth_sendPqcTransaction", [signedPayload]);
  const summary = buildTxSummary(txHash, signedPayload);

  console.log(
    JSON.stringify(
      {
        chainId: BigInt(chainIdHex).toString(),
        currentBlock: BigInt(blockNumberHex).toString(),
        pqcSender: signedPayload.from,
        ...summary,
      },
      null,
      2
    )
  );
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
