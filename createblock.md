# Create Block Demo Notes

## Muc tieu

Tai lieu nay tong hop cach setup de Besu local:

- tu tao block moi
- chap nhan PQC transaction
- dua transaction vao block
- tra ve `receipt` thanh cong
- hien thi `from` khop voi dia chi PQC sender

Day la setup phuc vu demo/PoC, khong phai mot mang QBFT production-day-du.

## Ket luan ngan gon

He thong hien tai dang dung:

- consensus: `QBFT`
- loai mang: `PoA/BFT private chain`
- so node demo: `1`
- node dang chay: vua la full node, vua la RPC node, vua la validator

Noi ngan gon:

- khong phai PoW
- khong phai mining kieu dao hash
- day la validator QBFT tu produce block

## Vi sao Besu tao duoc block

Besu tao block khong phai vi co request RPC gui vao, ma vi:

1. `genesis.json` khai bao chain nay dung `QBFT`
2. `genesis.json` co validator set trong `extraData`
3. node dang chay bang dung `validator private key`
4. consensus QBFT cho phep validator do propose block theo `blockperiodseconds`

Neu co transaction hop le trong tx pool thi block se include transaction do.
Neu khong co transaction thi node van co the tao empty block.

## Vi sao truoc day block khong tang

Co 2 van de chinh da duoc tim ra:

1. `setup.sh` truoc day lay sai validator key path
2. data dir cu bi mismatch voi genesis moi

### Loi key path cu

Script cu tro vao mot duong dan kieu:

```bash
.besu-pqc-poa/keys/0/key.priv
```

Nhung `besu operator generate-blockchain-config` sinh key validator theo dang:

```bash
.besu-pqc-poa/keys/<validator-address>/key.priv
```

Vi vay node khong dung dung validator key cua genesis.

### Loi data dir cu

Khi doi genesis ma van dung data dir cu, Besu co the gap loi kieu:

```text
Supplied genesis block does not match chain data stored in database
```

Hoac node len duoc nhung hanh vi debug se rat kho hieu.

## Ban sua da lam

### 1. Sua `setup.sh`

`setup.sh` hien tai:

- tu tim validator key that tu `keys/*/key.priv`
- cho phep override `DATA_DIR`
- tao/chay local QBFT node dung key va genesis

Doan quan trong:

```bash
validator_key_file() {
  local validator_key

  validator_key="$(compgen -G "$NETWORK_DIR/keys/*/key.priv" | head -n 1 || true)"
  if [[ -z "$validator_key" ]]; then
    echo "Validator private key not found under $NETWORK_DIR/keys" >&2
    exit 1
  fi

  printf '%s\n' "$validator_key"
}
```

Va khi chay Besu:

```bash
--node-private-key-file="$node_private_key_file"
--genesis-file="$GENESIS_FILE"
--data-path="$DATA_DIR"
```

### 2. Sua JSON-RPC de `from` khop PQC sender

Van de cu:

- luc submit PQC tx, Besu verify dung sender PQC
- nhung khi tx duoc doc lai tu block, `Transaction.getSender()` recover tu dummy ECDSA signature
- ket qua `from` hien ra sai dia chi

Ban sua hien tai dung mot registry trong memory:

- luu `txHash -> pqcSender` luc `eth_sendPqcTransaction` accept tx
- override field `from` trong:
  - `eth_getTransactionByHash`
  - `eth_getTransactionReceipt`

Muc tieu cua ban sua nay la:

- de demo cuoi cung hien thi nhat quan sender PQC

## File da them/sua

### File moi

- `final_client.js`
- `final_check.sh`
- `besu/ethereum/api/src/main/java/org/hyperledger/besu/ethereum/api/jsonrpc/internal/methods/PqcTransactionRegistry.java`

### File da sua

- `setup.sh`
- `besu/ethereum/api/src/main/java/org/hyperledger/besu/ethereum/api/jsonrpc/internal/methods/EthSendPqcTransaction.java`
- `besu/ethereum/api/src/main/java/org/hyperledger/besu/ethereum/api/jsonrpc/internal/methods/EthGetTransactionByHash.java`
- `besu/ethereum/api/src/main/java/org/hyperledger/besu/ethereum/api/jsonrpc/internal/methods/EthGetTransactionReceipt.java`
- `besu/ethereum/api/src/main/java/org/hyperledger/besu/ethereum/api/jsonrpc/internal/results/TransactionBaseResult.java`
- `besu/ethereum/api/src/main/java/org/hyperledger/besu/ethereum/api/jsonrpc/internal/results/TransactionWithMetadataResult.java`
- `besu/ethereum/api/src/main/java/org/hyperledger/besu/ethereum/api/jsonrpc/internal/results/TransactionReceiptResult.java`
- `besu/ethereum/api/src/main/java/org/hyperledger/besu/ethereum/api/jsonrpc/internal/results/TransactionReceiptStatusResult.java`
- `besu/ethereum/api/src/main/java/org/hyperledger/besu/ethereum/api/jsonrpc/internal/results/TransactionReceiptRootResult.java`

## Cach chay final demo

### 1. Build Besu va PQC helper

```bash
bash setup.sh build
```

### 2. Chay Besu voi data dir sach

Terminal 1:

```bash
DATA_DIR="$PWD/besu-pqc-poa-data-final" bash setup.sh run
```

Neu muon chay nen:

```bash
setsid env DATA_DIR="$PWD/besu-pqc-poa-data-final" bash setup.sh run > .setup-final.log 2>&1 < /dev/null &
```

### 3. Kiem tra block co dang tang khong

Terminal 2:

```bash
bash final_check.sh status
```

Dau hieu dung:

```text
Current block: 131
Watching for a new block for up to 20s...
New block detected: 132 -> 134
```

### 4. Gui PQC transaction

```bash
node final_client.js
```

Muc dich cua `final_client.js`:

- gui mot account-based transaction that hon ban cu
- `to` mac dinh la `0x1111111111111111111111111111111111111111`
- `value` mac dinh la `1 wei`
- van dung flow ky PQC hien tai

Vi du output:

```json
{
  "sender": "0x7a4e1bd68b8aea6c04f0452cbfef26ba22e2234d",
  "recipient": "0x1111111111111111111111111111111111111111",
  "transaction": {
    "hash": "0xe1df0a572037360a72e5fb7708abaea3ffb9a7795276e65a9912bdb328d3e92b",
    "valueWei": "1"
  }
}
```

### 5. Kiem tra transaction da vao block chua

```bash
bash final_check.sh tx 0xe1df0a572037360a72e5fb7708abaea3ffb9a7795276e65a9912bdb328d3e92b
```

Dau hieu thanh cong:

- `eth_getTransactionByHash.result.blockNumber` khac `null`
- `eth_getTransactionReceipt.result` khac `null`
- `status = 0x1`
- `from = 0x7a4e1bd68b8aea6c04f0452cbfef26ba22e2234d`

### 6. Kiem tra mot block cu the

```bash
bash final_check.sh block latest
```

Hoac theo so block:

```bash
bash final_check.sh block 10
```

Lenh nay goi `eth_getBlockByNumber(..., true)` de hien thi full block va danh sach transaction trong block do.

### 7. Dump toan bo chain tu genesis den latest

```bash
bash final_check.sh chain
```

Lenh nay se lan luot goi `eth_getBlockByNumber` cho tat ca block tu `0` den `latest`.
Phu hop khi can xem toan bo blockchain local nho de demo hoac debug.

## Ket qua cuoi cung da xac nhan

Khi test cuoi, he thong da cho:

### `eth_getTransactionByHash`

```json
{
  "from": "0x7a4e1bd68b8aea6c04f0452cbfef26ba22e2234d",
  "to": "0x1111111111111111111111111111111111111111",
  "value": "0x1",
  "blockNumber": "0x92"
}
```

### `eth_getTransactionReceipt`

```json
{
  "from": "0x7a4e1bd68b8aea6c04f0452cbfef26ba22e2234d",
  "to": "0x1111111111111111111111111111111111111111",
  "status": "0x1",
  "blockNumber": "0x92"
}
```

Dieu nay chung minh:

1. client ky PQC transaction thanh cong
2. Besu verify PQC transaction thanh cong
3. transaction vao pending pool
4. QBFT validator tao block moi
5. transaction duoc include vao block
6. receipt tra ve thanh cong
7. `from` hien thi khop PQC sender

## Giai thich ngan gon co che tao block hien tai

Setup hien tai la:

- `QBFT`
- `1 node`
- `1 validator`

Node dang chay hien tai dong thoi la:

1. RPC node
2. full node
3. validator

Noi cach khac:

- node nay tu nhan tx
- tu verify tx
- tu giu tx trong pool
- tu produce block
- tu include tx vao block

## Day la PoW hay PoA?

La `PoA`, cu the hon la `QBFT`.

Khong phai `PoW`.

Khong co chuyen dao hash/difficulty/hashrate o day.

Node tao block vi no la validator hop le theo genesis va key, khong phai vi no "mine" theo nghia PoW.

## Tai sao tai lieu thuong noi 4 nodes?

Vi neu noi dung-y-nghia BFT that su thi thuong can nhieu validator.

Tai lieu hay noi 4 nodes vi:

- muon co fault tolerance that
- muon mo phong dong thuan nhieu ben that

Con setup hien tai chi la:

- single-node QBFT local demo

No dung cho PoC vi:

- gon
- de chay
- co block
- co receipt
- de chung minh PQC tx duoc confirm

Nhung no khong the hien day du fault tolerance cua BFT that su.

## Besu dua tren gi de chap nhan tao block?

Besu dua tren:

1. `genesis`
2. `validator set`
3. `validator private key`
4. `QBFT consensus rules`

No khong tao block chi vi co request RPC.

## Neu request fake thi sao?

Neu transaction fake thi Besu van phai verify truoc:

- chu ky dung khong
- nonce dung khong
- balance du khong
- chainId dung khong
- gas hop le khong
- trong flow nay: PQC signature verify dung khong

Neu fake:

- transaction bi reject
- hoac khong duoc include vao block

Noi ngan gon:

- co request gui den node khong dong nghia request se vao block

## Cac lenh can nho

### Build

```bash
bash setup.sh build
```

### Run

```bash
DATA_DIR="$PWD/besu-pqc-poa-data-final" bash setup.sh run
```

### Check block

```bash
bash final_check.sh
```

### Send tx

```bash
node final_client.js
```

### Check tx

```bash
bash final_check.sh 0xTX_HASH
```

## Luu y nho

`final_check.sh` hien tai co the in `Latest balance (wei)` thanh so am khi so qua lon.

Day la loi format shell arithmetic overflow, khong phai balance that bi am.

Logic chain va transaction van dung.

## Cau mo ta ngan gon de bao cao

Co the noi nhu sau:

"Em setup Besu local theo single-node QBFT. Node local nay dong thoi la validator duoc khai bao trong genesis va chay bang dung validator private key, nen no co quyen produce block. Sau do client gui transaction ky bang Dilithium thong qua `eth_sendPqcTransaction`, Besu verify chu ky PQC, dua transaction vao txpool, produce block moi, va transaction nhan duoc receipt thanh cong on-chain."
