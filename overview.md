# Besu PQC Overview

## Tong quan

Workspace nay da duoc chuyen thanh mot PoC theo dung huong de tai trong `project.md`: custom Hyperledger Besu de chap nhan giao dich duoc ky bang Post-Quantum Cryptography, cu the la Dilithium thong qua Bouncy Castle PQC provider.

Thay vi sua toan bo raw transaction pipeline cua Ethereum, PoC nay custom theo huong it xam lan nhat:

- giu nguyen phan lon transaction pool va execution pipeline cua Besu
- them mot JSON-RPC method moi `eth_sendPqcTransaction`
- verify chu ky PQC ngay trong node
- derive dia chi sender tu public key PQC de van map vao mo hinh account cua EVM

Huong nay phu hop voi muc tieu do an vi no chung minh duoc kha nang crypto-agility tren node blockchain ma khong can lam hard fork day du transaction format.

## Nhung gi da co trong Besu fork

### 1. Them thu vien PQC vao Besu

Trong `besu/ethereum/api/build.gradle` da co bo sung:

```gradle
implementation 'org.bouncycastle:bcpqc-jdk18on:1.83'
```

Y nghia:

- node Besu co the dung Bouncy Castle PQC provider
- server co the verify chu ky Dilithium thay vi chi phu thuoc secp256k1 mac dinh

### 2. Them RPC method moi `eth_sendPqcTransaction`

Phan custom trong Besu nam o cac file:

- `besu/ethereum/api/src/main/java/org/hyperledger/besu/ethereum/api/jsonrpc/RpcMethod.java`
- `besu/ethereum/api/src/main/java/org/hyperledger/besu/ethereum/api/jsonrpc/methods/EthJsonRpcMethods.java`
- `besu/ethereum/api/src/main/java/org/hyperledger/besu/ethereum/api/jsonrpc/internal/methods/EthSendPqcTransaction.java`

`eth_sendPqcTransaction` nhan mot object gom:

- `from`
- `to`
- `value`
- `gasLimit`
- `gasPrice`
- `nonce`
- `chainId`
- `data`
- `pqcPublicKey`
- `pqcSignature`

Node se:

- parse payload
- derive sender tu `pqcPublicKey`
- canonicalize message
- verify `pqcSignature`
- build `Transaction` noi bo cua Besu
- dua transaction vao `TransactionPool`

### 3. Helper PQC trong Besu

`besu/ethereum/api/src/main/java/org/hyperledger/besu/ethereum/api/jsonrpc/internal/methods/PqcCrypto.java` dam nhiem:

- nap `BouncyCastleProvider` va `BouncyCastlePQCProvider`
- verify chu ky `DILITHIUM`
- chuan hoa cac field hex va quantity
- hash canonical payload bang `SHA-256`
- derive sender address tu `keccak256(publicKeyPqc)`

Day la tam diem ky thuat cua phan custom thuat toan vao thu vien/node.

### 4. Transaction pipeline van duoc tai su dung

Sau khi verify PQC thanh cong, `EthSendPqcTransaction` tao `Transaction.builder()` va set:

- `nonce`
- `gasPrice`
- `gasLimit`
- `to`
- `value`
- `payload`
- `chainId`
- `sender`
- `signature(DUMMY_SIGNATURE)`

Y nghia thuc te:

- Besu van validate transaction trong txpool
- van co block production tren `--network=dev`
- PoC chi custom diem vao cua chu ky, khong can viet lai execution engine

## Nhung gi da duoc hoan thien them trong workspace

### 1. `setup.sh`

Da hoan thien `setup.sh` de phuc vu build va run theo luong reproducible:

- tu dong dung local JDK tai `.local-jdk/jdk-21.0.10+7` neu co
- build Besu bang `gradlew installDist`
- compile helper `PqcSigner.java` vao `.build/pqc-signer`
- kiem tra su ton tai cua Besu source, Besu distribution va cac command can thiet
- run node Besu dev mode voi HTTP RPC bat san

Lenh ho tro:

```bash
bash setup.sh build
bash setup.sh run
bash setup.sh all
```

### 2. `client.js`

Da cap nhat `client.js` de tro thanh mot demo client dung duoc voi Besu custom:

- goi Java helper da compile san thay vi co gang chay truc tiep file `.java`
- lay dia chi PQC tu cung mot cap khoa Dilithium duoc luu trong `.pqc-wallet`
- truy van `eth_getTransactionCount(..., "pending")` de lay nonce dong truoc khi ky
- dung classpath gom `.build/pqc-signer` va `besu/build/install/besu/lib/*`
- goi `eth_chainId`, `eth_blockNumber`
- tao payload transaction toi dia chi `0x0000000000000000000000000000000000000000` de tranh contract-creation voi `21000 gas`
- gui payload da ky qua `eth_sendPqcTransaction`

Luu y quan trong:

- canonical message co chua `nonce`
- vi vay client bat buoc lay nonce truoc khi ky
- flow hien tai da ho tro gui nhieu giao dich lien tiep bang cung mot PQC account, mien la moi lan deu query nonce moi roi ky lai

### 3. `PqcSigner.java`

File nay dong vai tro wallet-side helper:

- nap Bouncy Castle classic va PQC provider
- sinh hoac tai lai keypair `DILITHIUM5`
- luu private/public key vao `.pqc-wallet`
- ho tro mode `address` de client lay sender address truoc khi ky
- derive dia chi sender tu public key
- canonicalize payload giong node
- ky message bang `DILITHIUM`
- tra ve JSON payload hoan chinh cho `eth_sendPqcTransaction`

Phan nay rat quan trong trong do an vi no mo phong wallet/client PQC tach biet voi node.

### 4. `overview.md`

Tai lieu nay da duoc viet lai theo huong chi tiet hon, tach ro:

- phan custom nam trong Besu fork
- phan helper/client nam o root workspace
- gioi han hien tai cua prototype
- cach build, run, demo

## Luong xu ly end-to-end

1. Chay `bash setup.sh build` de build Besu va compile helper signer.
2. Chay `bash setup.sh run` de khoi dong Besu dev node.
3. Chay `node client.js`.
4. `client.js` goi `PqcSigner` de lay dia chi PQC.
5. Client gui payload toi `eth_sendPqcTransaction`.
6. Client lay nonce hien tai tu node, tao payload, roi goi `PqcSigner` de ky.
7. Besu verify chu ky PQC trong `PqcCrypto.verify(...)`.
8. Neu hop le, Besu tao `Transaction` noi bo va dua vao txpool.
9. Node dev mine transaction va tra ve transaction hash.

## Gioi han hien tai cua PoC

Day la phan rat quan trong de trinh bay trong bao cao do an.

PoC hien tai chua phai mot su thay the hoan chinh cho secp256k1 trong Ethereum protocol:

- khong sua dinh dang raw transaction chuan
- khong thay the `eth_sendRawTransaction`
- khong chinh sua co che sender recovery goc cua Ethereum
- chua co transaction type rieng cho PQC
- hien dang dung custom RPC de dua payload vao node
- client van dung custom RPC, chua phai raw transaction type goc cua Ethereum

Tuy vay, PoC van dat gia tri hoc thuat ro rang:

- chung minh node co the xac thuc giao dich PQC that su tren server
- the hien mot duong migration theo huong crypto-agility
- la nen tang de mo rong thanh hybrid mode ECC + PQC

## Danh sach file chinh

- `setup.sh`
- `client.js`
- `PqcSigner.java`
- `overview.md`
- `besu/ethereum/api/build.gradle`
- `besu/ethereum/api/src/main/java/org/hyperledger/besu/ethereum/api/jsonrpc/RpcMethod.java`
- `besu/ethereum/api/src/main/java/org/hyperledger/besu/ethereum/api/jsonrpc/methods/EthJsonRpcMethods.java`
- `besu/ethereum/api/src/main/java/org/hyperledger/besu/ethereum/api/jsonrpc/internal/methods/PqcTransactionParams.java`
- `besu/ethereum/api/src/main/java/org/hyperledger/besu/ethereum/api/jsonrpc/internal/methods/PqcCrypto.java`
- `besu/ethereum/api/src/main/java/org/hyperledger/besu/ethereum/api/jsonrpc/internal/methods/EthSendPqcTransaction.java`

## Huong mo rong tiep theo

1. Tu dong lay nonce truoc, sau do ky payload moi thay vi co dinh `0x0`.
2. Them che do hybrid dual-signature de so sanh ECC va PQC.
3. Dong goi PQC thanh transaction type rieng thay vi custom RPC.
4. Bo sung benchmark: sign latency, verify latency, tx size, TPS, block size.
5. Thu nghiem them SPHINCS+ hoac Falcon de so sanh trade-off ve kich thuoc va toc do.
