package io.nuls.api.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.nuls.api.ApiContext;
import io.nuls.api.cache.ApiCache;
import io.nuls.api.constant.ApiConstant;
import io.nuls.api.constant.CommandConstant;
import io.nuls.api.manager.CacheManager;
import io.nuls.api.model.entity.*;
import io.nuls.api.model.po.*;
import io.nuls.api.model.po.mini.DelayStopAgent;
import io.nuls.api.rpc.RpcCall;
import io.nuls.api.utils.LoggerUtil;
import io.nuls.base.RPCUtil;
import io.nuls.base.basic.AddressTool;
import io.nuls.base.basic.NulsByteBuffer;
import io.nuls.base.data.*;
import io.nuls.core.basic.Result;
import io.nuls.core.constant.CommonCodeConstanst;
import io.nuls.core.constant.TxStatusEnum;
import io.nuls.core.constant.TxType;
import io.nuls.core.crypto.HexUtil;
import io.nuls.core.exception.NulsException;
import io.nuls.core.parse.JSONUtils;
import io.nuls.core.rpc.info.Constants;
import io.nuls.core.rpc.model.ModuleE;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.nuls.api.constant.ApiConstant.*;

public class AnalysisHandler {

    /**
     * Convert block information to blockInfo information
     * 将block信息转换为blockInfo信息
     *
     * @param blockHex
     * @param chainId
     * @return
     * @throws Exception
     */
    public static BlockInfo toBlockInfo(String blockHex, int chainId) throws Exception {
        byte[] bytes = RPCUtil.decode(blockHex);
        Block block = new Block();
        block.parse(new NulsByteBuffer(bytes));

        BlockInfo blockInfo = new BlockInfo();
        BlockHeaderInfo blockHeader = toBlockHeaderInfo(block.getHeader(), chainId);
        blockHeader.setSize(bytes.length);
        blockHeader.setTxHashList(new ArrayList<>());
        //提取智能合约相关交易的hash，查询合约执行结果
        //Extract the hash of smart contract related transactions and query the contract execution results
        List<String> contactHashList = new ArrayList<>();
        if (ApiContext.isRunSmartContract) {
            for (Transaction tx : block.getTxs()) {
                if (tx.getType() == TxType.CREATE_CONTRACT ||
                        tx.getType() == TxType.CALL_CONTRACT ||
                        tx.getType() == TxType.DELETE_CONTRACT ||
                        tx.getType() == TxType.CROSS_CHAIN) {
                    contactHashList.add(tx.getHash().toHex());
                }
            }
        }

        BlockHexInfo hexInfo = new BlockHexInfo();
        hexInfo.setHeight(blockHeader.getHeight());
        hexInfo.setBlockHex(blockHex);
        hexInfo.setContractHashList(contactHashList);
        blockInfo.setBlockHexInfo(hexInfo);

        Map<String, ContractResultInfo> resultInfoMap = null;
//        LoggerUtil.commonLog.warn("-=-=-=-{}",JSONUtils.obj2json(contactHashList));
        if (!contactHashList.isEmpty()) {
            Result<Map<String, ContractResultInfo>> result = WalletRpcHandler.getContractResults(chainId, contactHashList);
            if (result.isFailed()) {
                return null;
            } else {
                resultInfoMap = result.getData();
            }
        }
        //执行成功的智能合约可能会产生系统内部交易，内部交易的序列化信息存放在执行结果中,将内部交易反序列后，一起解析
        //A successful intelligent contract execution may result in system internal trading.
        // The serialized information of internal trading is stored in the execution result, and the internal trading is reversed and parsed together
//        LoggerUtil.commonLog.warn("-=-=-=-{}",JSONUtils.obj2json(resultInfoMap));
        if (resultInfoMap != null) {
            for (ContractResultInfo resultInfo : resultInfoMap.values()) {
                if (resultInfo.getContractTxList() != null) {
                    for (String txHex : resultInfo.getContractTxList()) {
                        Transaction tx = new Transaction();
                        tx.parse(new NulsByteBuffer(RPCUtil.decode(txHex)));
                        tx.setBlockHeight(blockHeader.getHeight());
                        block.getTxs().add(tx);
                        // blockInfo.getTxList().add(toTransaction(chainId, tx));
                    }
                }
            }
        }
        blockInfo.setTxList(toTxs(chainId, block.getTxs(), blockHeader, resultInfoMap));
        //计算coinBase奖励
        blockHeader.setReward(calcCoinBaseReward(chainId, blockInfo.getTxList().get(0)));
        //计算总手续费
        blockHeader.setTotalFee(calcFee(blockInfo.getTxList(), chainId));
        //重新计算区块打包的交易个数
        blockHeader.setTxCount(blockInfo.getTxList().size());
        blockInfo.setHeader(blockHeader);
        return blockInfo;
    }

    public static BlockInfo toBlockInfo(String blockHex, Map<String, ContractResultInfo> resultInfoMap, int chainId) throws Exception {
        byte[] bytes = RPCUtil.decode(blockHex);
        Block block = new Block();
        block.parse(new NulsByteBuffer(bytes));

        BlockInfo blockInfo = new BlockInfo();
        BlockHeaderInfo blockHeader = toBlockHeaderInfo(block.getHeader(), chainId);
        blockHeader.setSize(bytes.length);
        blockHeader.setTxHashList(new ArrayList<>());

        //执行成功的智能合约可能会产生系统内部交易，内部交易的序列化信息存放在执行结果中,将内部交易反序列后，一起解析
        //A successful intelligent contract execution may result in system internal trading.
        // The serialized information of internal trading is stored in the execution result, and the internal trading is reversed and parsed together
        if (resultInfoMap != null) {
            for (ContractResultInfo resultInfo : resultInfoMap.values()) {
                if (resultInfo.getContractTxList() != null) {
                    for (String txHex : resultInfo.getContractTxList()) {
                        Transaction tx = new Transaction();
                        tx.parse(new NulsByteBuffer(RPCUtil.decode(txHex)));
                        tx.setBlockHeight(blockHeader.getHeight());
                        block.getTxs().add(tx);
                    }
                }
            }
        }
        blockInfo.setTxList(toTxs(chainId, block.getTxs(), blockHeader, resultInfoMap));
        //计算coinBase奖励
        blockHeader.setReward(calcCoinBaseReward(chainId, blockInfo.getTxList().get(0)));
        //计算总手续费
        blockHeader.setTotalFee(calcFee(blockInfo.getTxList(), chainId));
        //重新计算区块打包的交易个数
        blockHeader.setTxCount(blockInfo.getTxList().size());
        blockInfo.setHeader(blockHeader);
        return blockInfo;
    }


    public static BlockHeaderInfo toBlockHeaderInfo(BlockHeader blockHeader, int chainId) throws IOException {
        BlockExtendsData extendsData = blockHeader.getExtendsData();

        BlockHeaderInfo info = new BlockHeaderInfo();
        info.setHash(blockHeader.getHash().toHex());
        info.setHeight(blockHeader.getHeight());
        info.setPreHash(blockHeader.getPreHash().toHex());
        info.setMerkleHash(blockHeader.getMerkleHash().toHex());
        info.setCreateTime(blockHeader.getTime());
        info.setPackingAddress(AddressTool.getStringAddressByBytes(blockHeader.getPackingAddress(chainId)));
        info.setTxCount(blockHeader.getTxCount());
        info.setRoundIndex(extendsData.getRoundIndex());
        info.setPackingIndexOfRound(extendsData.getPackingIndexOfRound());
        info.setScriptSign(HexUtil.encode(blockHeader.getBlockSignature().serialize()));
        info.setAgentVersion(extendsData.getBlockVersion());
        info.setMainVersion(extendsData.getMainVersion());
        info.setRoundStartTime(extendsData.getRoundStartTime());
        //是否是种子节点打包的区块
        ApiCache apiCache = CacheManager.getCache(chainId);
        if (apiCache.getChainInfo().getSeeds().contains(info.getPackingAddress()) || info.getHeight() == 0) {
            info.setSeedPacked(true);
        }
        return info;
    }

    public static List<TransactionInfo> toTxs(int chainId, List<Transaction> txList, BlockHeaderInfo blockHeader, Map<String, ContractResultInfo> resultInfoMap) throws Exception {
        List<TransactionInfo> txs = new ArrayList<>();
        for (int i = 0; i < txList.size(); i++) {
            Transaction tx = txList.get(i);
            tx.setStatus(TxStatusEnum.CONFIRMED);
            TransactionInfo txInfo = toTransaction(chainId, tx, resultInfoMap, blockHeader.getMainVersion());
            if (txInfo.getType() == TxType.RED_PUNISH) {
                PunishLogInfo punishLog = (PunishLogInfo) txInfo.getTxData();
                punishLog.setRoundIndex(blockHeader.getRoundIndex());
                punishLog.setPackageIndex(blockHeader.getPackingIndexOfRound());
            } else if (txInfo.getType() == TxType.YELLOW_PUNISH) {
                for (TxDataInfo txData : txInfo.getTxDataList()) {
                    PunishLogInfo punishLog = (PunishLogInfo) txData;
                    punishLog.setRoundIndex(blockHeader.getRoundIndex());
                    punishLog.setPackageIndex(blockHeader.getPackingIndexOfRound());
                }
            }
            if (i != 0) {
                txInfo.setCreateTime(blockHeader.getCreateTime() - (txs.size() - i));
            }
            txs.add(txInfo);
            blockHeader.getTxHashList().add(txInfo.getHash());
        }
        return txs;
    }

    public static TransactionInfo toTransaction(int chainId, Transaction tx, int version) throws Exception {
        TransactionInfo info = new TransactionInfo();
        info.setHash(tx.getHash().toHex());
        info.setHeight(tx.getBlockHeight());
        info.setType(tx.getType());
        info.setSize(tx.getSize());
        info.setCreateTime(tx.getTime());
        if (tx.getTxData() != null) {
            info.setTxDataHex(RPCUtil.encode(tx.getTxData()));
        }
        if (tx.getRemark() != null) {
            info.setRemark(new String(tx.getRemark(), StandardCharsets.UTF_8));
        }
        if (tx.getStatus() == TxStatusEnum.CONFIRMED) {
            info.setStatus(ApiConstant.TX_CONFIRM);
        } else {
            info.setStatus(ApiConstant.TX_UNCONFIRM);
        }

        CoinData coinData = new CoinData();
        if (tx.getCoinData() != null) {
            coinData.parse(new NulsByteBuffer(tx.getCoinData()));
            info.setCoinFroms(toCoinFromList(coinData));
            info.setCoinTos(toCoinToList(coinData));
        }
        if (info.getType() == TxType.YELLOW_PUNISH) {
            info.setTxDataList(toYellowPunish(tx));
        } else {
            info.setTxData(toTxData(chainId, tx, version));
        }
        info.calcValue(chainId);
        info.calcFee(chainId);
        return info;
    }

    public static TransactionInfo toTransaction(int chainId, Transaction tx, Map<String, ContractResultInfo> resultInfoMap, int version) throws Exception {
        TransactionInfo info = new TransactionInfo();
        info.setHash(tx.getHash().toHex());
        info.setHeight(tx.getBlockHeight());
        info.setType(tx.getType());
        info.setSize(tx.getSize());
        info.setCreateTime(tx.getTime());
        if (tx.getTxData() != null) {
            info.setTxDataHex(RPCUtil.encode(tx.getTxData()));
        }
        if (tx.getRemark() != null) {
            info.setRemark(new String(tx.getRemark(), StandardCharsets.UTF_8));
        }

        CoinData coinData = new CoinData();
        if (tx.getCoinData() != null) {
            coinData.parse(new NulsByteBuffer(tx.getCoinData()));
            info.setCoinFroms(toCoinFromList(coinData));
            info.setCoinTos(toCoinToList(coinData));
        }
        ContractResultInfo resultInfo = null;
        if (resultInfoMap != null) {
            resultInfo = resultInfoMap.get(info.getHash());
        }
        if (resultInfo == null && tx.getType() == 16) {
            throw new Exception("-----执行合约未查询到智能合约执行结果 交易hash: " + tx.getHash());
        }
        if (resultInfo == null) {
            if (info.getType() == TxType.YELLOW_PUNISH) {
                info.setTxDataList(toYellowPunish(tx));
            } else {
                info.setTxData(toTxData(chainId, tx, version));
            }
        } else {
            info.setTxData(toTxData(chainId, tx, resultInfo));
        }
        info.calcValue(chainId);
        info.calcFee(chainId);
        if (tx.getStatus() == TxStatusEnum.UNCONFIRM) {
            info.setStatus(ApiConstant.TX_UNCONFIRM);
        } else {
            info.setStatus(ApiConstant.TX_CONFIRM);
        }
        return info;
    }

    public static List<CoinFromInfo> toCoinFromList(CoinData coinData) {
        if (coinData == null || coinData.getFrom() == null) {
            return null;
        }
        List<CoinFromInfo> fromInfoList = new ArrayList<>();
        for (CoinFrom from : coinData.getFrom()) {
            CoinFromInfo fromInfo = new CoinFromInfo();
            fromInfo.setAddress(AddressTool.getStringAddressByBytes(from.getAddress()));
            fromInfo.setAssetsId(from.getAssetsId());
            fromInfo.setChainId(from.getAssetsChainId());
            fromInfo.setLocked(from.getLocked());
            fromInfo.setAmount(from.getAmount());
            fromInfo.setNonce(HexUtil.encode(from.getNonce()));
            AssetInfo assetInfo = CacheManager.getRegisteredAsset(fromInfo.getAssetKey());
            fromInfo.setSymbol(assetInfo.getSymbol());
            fromInfo.setDecimal(assetInfo.getDecimals());
            fromInfoList.add(fromInfo);
        }
        return fromInfoList;
    }

    public static List<CoinToInfo> toCoinToList(CoinData coinData) {
        if (coinData == null || coinData.getTo() == null) {
            return null;
        }
        List<CoinToInfo> toInfoList = new ArrayList<>();
        for (CoinTo to : coinData.getTo()) {
            CoinToInfo coinToInfo = new CoinToInfo();
            coinToInfo.setAddress(AddressTool.getStringAddressByBytes(to.getAddress()));
            coinToInfo.setAssetsId(to.getAssetsId());
            coinToInfo.setChainId(to.getAssetsChainId());
            coinToInfo.setLockTime(to.getLockTime());
            coinToInfo.setAmount(to.getAmount());
            AssetInfo assetInfo = CacheManager.getRegisteredAsset(coinToInfo.getAssetKey());
            coinToInfo.setSymbol(assetInfo.getSymbol());
            coinToInfo.setDecimal(assetInfo.getDecimals());
            toInfoList.add(coinToInfo);
        }
        return toInfoList;
    }

    public static TxDataInfo toTxData(int chainId, Transaction tx, int version) throws NulsException {
        if (tx.getType() == TxType.ACCOUNT_ALIAS) {
            return toAlias(tx);
        } else if (tx.getType() == TxType.REGISTER_AGENT || tx.getType() == TxType.CONTRACT_CREATE_AGENT) {
            return toAgent(tx);
        } else if (tx.getType() == TxType.DEPOSIT || tx.getType() == TxType.CONTRACT_DEPOSIT) {
            return toDeposit(tx);
        } else if (tx.getType() == TxType.CANCEL_DEPOSIT || tx.getType() == TxType.CONTRACT_CANCEL_DEPOSIT) {
            return toCancelDeposit(tx);
        } else if (tx.getType() == TxType.STOP_AGENT || tx.getType() == TxType.CONTRACT_STOP_AGENT) {
            return toStopAgent(tx);
        } else if (tx.getType() == TxType.RED_PUNISH) {
            return toRedPublishLog(tx);
        } else if (tx.getType() == TxType.CREATE_CONTRACT) {
            return toContractInfo(chainId, tx);
        } else if (tx.getType() == TxType.CALL_CONTRACT) {
            return toContractCallInfo(chainId, tx);
        } else if (tx.getType() == TxType.CROSS_CHAIN) {
            // add by pierre at 2019-12-23 特殊跨链转账交易，从平行链跨链转回主网的NRC20资产
            return toContractCallInfoForCrossChain(chainId, tx);
            // end code by pierre
        } else if (tx.getType() == TxType.DELETE_CONTRACT) {
            return toContractDeleteInfo(chainId, tx);
        } else if (tx.getType() == TxType.CONTRACT_TRANSFER) {
            return toContractTransferInfo(tx);
        } else if (tx.getType() == TxType.REGISTER_CHAIN_AND_ASSET || tx.getType() == TxType.DESTROY_CHAIN_AND_ASSET) {
            return toChainInfo(tx, version);
        } else if (tx.getType() == TxType.ADD_ASSET_TO_CHAIN || tx.getType() == TxType.REMOVE_ASSET_FROM_CHAIN) {
            return toAssetInfo(tx, version);
        } else if (tx.getType() == 34) {
            return toDelayStopAgent(tx, version);
        }
        return null;
    }

    private static DelayStopAgentInfo toDelayStopAgent(Transaction tx, int version) throws NulsException {
        DelayStopAgent alias = new DelayStopAgent();
        alias.parse(new NulsByteBuffer(tx.getTxData()));
        DelayStopAgentInfo info = new DelayStopAgentInfo();
        info.setAgentHash(alias.getAgentHash());
        info.setHeight(alias.getHeight());
        return info;
    }

    public static TxDataInfo toTxData(int chainId, Transaction tx, ContractResultInfo resultInfo) throws NulsException {
        if (tx.getType() == TxType.CREATE_CONTRACT) {
            return toContractInfo(chainId, tx, resultInfo);
        } else if (tx.getType() == TxType.CALL_CONTRACT) {
            return toContractCallInfo(chainId, tx, resultInfo);
        } else if (tx.getType() == TxType.CROSS_CHAIN) {
            return toContractCallInfoForCrossChain(chainId, tx, resultInfo);
        } else if (tx.getType() == TxType.DELETE_CONTRACT) {
            return toContractDeleteInfo(chainId, tx, resultInfo);
        }
        return null;
    }

    public static AliasInfo toAlias(Transaction tx) throws NulsException {
        Alias alias = new Alias();
        alias.parse(new NulsByteBuffer(tx.getTxData()));
        AliasInfo info = new AliasInfo();
        info.setAddress(AddressTool.getStringAddressByBytes(alias.getAddress()));
        info.setAlias(alias.getAlias());
        return info;
    }

    public static AgentInfo toAgent(Transaction tx) throws NulsException {
        Agent agent = new Agent();
        agent.parse(new NulsByteBuffer(tx.getTxData()));

        AgentInfo agentInfo = new AgentInfo();
        agentInfo.init();
        agentInfo.setAgentAddress(AddressTool.getStringAddressByBytes(agent.getAgentAddress()));
        agentInfo.setPackingAddress(AddressTool.getStringAddressByBytes(agent.getPackingAddress()));
        agentInfo.setRewardAddress(AddressTool.getStringAddressByBytes(agent.getRewardAddress()));
        agentInfo.setDeposit(agent.getDeposit());
        agentInfo.setCreateTime(tx.getTime());

        agentInfo.setCommissionRate(agent.getCommissionRate());
        agentInfo.setTxHash(tx.getHash().toHex());
        agentInfo.setAgentId(agentInfo.getTxHash().substring(agentInfo.getTxHash().length() - 8));
        agentInfo.setBlockHeight(tx.getBlockHeight());
        return agentInfo;
    }

    public static DepositInfo toDeposit(Transaction tx) throws NulsException {
        Deposit deposit = new Deposit();
        deposit.parse(new NulsByteBuffer(tx.getTxData()));

        DepositInfo info = new DepositInfo();
        info.setTxHash(tx.getHash().toHex());
        info.setAmount(deposit.getDeposit());
        info.setAgentHash(deposit.getAgentHash().toHex());
        info.setAddress(AddressTool.getStringAddressByBytes(deposit.getAddress()));
        info.setTxHash(tx.getHash().toHex());
        info.setCreateTime(tx.getTime());
        info.setBlockHeight(tx.getBlockHeight());
        info.setFee(tx.getFee());

        return info;
    }

    public static DepositInfo toCancelDeposit(Transaction tx) throws NulsException {
        CancelDeposit cancelDeposit = new CancelDeposit();
        cancelDeposit.parse(new NulsByteBuffer(tx.getTxData()));
        DepositInfo deposit = new DepositInfo();
        deposit.setTxHash(cancelDeposit.getJoinTxHash().toHex());
        deposit.setFee(tx.getFee());
        deposit.setCreateTime(tx.getTime());
        deposit.setBlockHeight(tx.getBlockHeight());
        deposit.setType(ApiConstant.CANCEL_CONSENSUS);
        return deposit;
    }

    public static AgentInfo toStopAgent(Transaction tx) throws NulsException {
        StopAgent stopAgent = new StopAgent();
        stopAgent.parse(new NulsByteBuffer(tx.getTxData()));

        AgentInfo agentNode = new AgentInfo();
        agentNode.setTxHash(stopAgent.getCreateTxHash().toHex());
        return agentNode;
    }

    public static List<TxDataInfo> toYellowPunish(Transaction tx) throws NulsException {
        YellowPunishData data = new YellowPunishData();
        data.parse(new NulsByteBuffer(tx.getTxData()));
        List<TxDataInfo> logList = new ArrayList<>();
        for (byte[] address : data.getAddressList()) {
            PunishLogInfo log = new PunishLogInfo();
            log.setTxHash(tx.getHash().toHex());
            log.setAddress(AddressTool.getStringAddressByBytes(address));
            log.setBlockHeight(tx.getBlockHeight());
            log.setTime(tx.getTime());
            log.setType(ApiConstant.PUBLISH_YELLOW);
            log.setReason("No packaged blocks");
            logList.add(log);
        }
        return logList;
    }

    public static PunishLogInfo toRedPublishLog(Transaction tx) throws NulsException {
        RedPunishData data = new RedPunishData();
        data.parse(new NulsByteBuffer(tx.getTxData()));

        PunishLogInfo punishLog = new PunishLogInfo();
        punishLog.setTxHash(tx.getHash().toHex());
        punishLog.setType(ApiConstant.PUBLISH_RED);
        punishLog.setAddress(AddressTool.getStringAddressByBytes(data.getAddress()));
        if (data.getReasonCode() == ApiConstant.TRY_FORK) {
            punishLog.setReason("Trying to bifurcate many times");
        } else if (data.getReasonCode() == ApiConstant.DOUBLE_SPEND) {
            punishLog.setReason("double-send tx in the block");
        } else if (data.getReasonCode() == ApiConstant.TOO_MUCH_YELLOW_PUNISH) {
            punishLog.setReason("too much yellow publish");
        }
        punishLog.setBlockHeight(tx.getBlockHeight());
        punishLog.setTime(tx.getTime());
        return punishLog;
    }

    public static ContractInfo toContractInfo(int chainId, Transaction tx) throws NulsException {
        CreateContractData data = new CreateContractData();
        data.parse(new NulsByteBuffer(tx.getTxData()));
        ContractInfo contractInfo = new ContractInfo();
        contractInfo.setCreateTxHash(tx.getHash().toHex());
        contractInfo.setContractAddress(AddressTool.getStringAddressByBytes(data.getContractAddress()));
        contractInfo.setAlias(data.getAlias());
        contractInfo.setBlockHeight(tx.getBlockHeight());
        contractInfo.setCreateTime(tx.getTime());
        try {
            String args = JSONUtils.obj2json(data.getArgs());
            contractInfo.setArgs(args);
        } catch (JsonProcessingException e) {
            throw new NulsException(CommonCodeConstanst.DATA_PARSE_ERROR);
        }
        if (tx.getStatus() == TxStatusEnum.CONFIRMED) {
            Result<ContractInfo> result = WalletRpcHandler.getContractInfo(chainId, contractInfo);
            return result.getData();
        }

        String remark = "";
        if (tx.getRemark() != null) {
            remark = new String(tx.getRemark(), StandardCharsets.UTF_8);
        }
        if (contractInfo.getResultInfo() != null && contractInfo.getResultInfo().getTokenTransfers() != null) {
            for (TokenTransfer tokenTransfer : contractInfo.getResultInfo().getTokenTransfers()) {
                tokenTransfer.setRemark(remark);
            }
        }
        return contractInfo;
    }

    public static ContractInfo toContractInfo(int chainId, Transaction tx, ContractResultInfo resultInfo) throws NulsException {
        CreateContractData data = new CreateContractData();
        data.parse(new NulsByteBuffer(tx.getTxData()));
        ContractInfo contractInfo = new ContractInfo();
        contractInfo.setCreateTxHash(tx.getHash().toHex());
        contractInfo.setAlias(data.getAlias());
        contractInfo.setContractAddress(AddressTool.getStringAddressByBytes(data.getContractAddress()));
        contractInfo.setBlockHeight(tx.getBlockHeight());
        contractInfo.setCreateTime(tx.getTime());
        try {
            String args = JSONUtils.obj2json(data.getArgs());
            contractInfo.setArgs(args);
        } catch (JsonProcessingException e) {
            throw new NulsException(CommonCodeConstanst.DATA_PARSE_ERROR);
        }
        contractInfo.setResultInfo(resultInfo);
        if (!resultInfo.isSuccess()) {
            contractInfo.setSuccess(false);
            contractInfo.setStatus(ApiConstant.CONTRACT_STATUS_FAIL);
            contractInfo.setErrorMsg(resultInfo.getErrorMessage());
            return contractInfo;
        }
        contractInfo.setStatus(ApiConstant.CONTRACT_STATUS_NORMAL);
        contractInfo.setSuccess(true);
        fillContractInfo(chainId, contractInfo);

        String remark = "";
        if (tx.getRemark() != null) {
            remark = new String(tx.getRemark(), StandardCharsets.UTF_8);
        }
        if (contractInfo.getResultInfo() != null && contractInfo.getResultInfo().getTokenTransfers() != null) {
            for (TokenTransfer tokenTransfer : contractInfo.getResultInfo().getTokenTransfers()) {
                tokenTransfer.setRemark(remark);
            }
        }
        return contractInfo;
    }

    private static ContractMethodArg makeContractMethodArg(Map<String, Object> arg) {
        return new ContractMethodArg((String) arg.get("type"), (String) arg.get("name"), (boolean) arg.get("required"));
    }

    public static ContractCallInfo toContractCallInfo(int chainId, Transaction tx) throws NulsException {
        CallContractData data = new CallContractData();
        data.parse(new NulsByteBuffer(tx.getTxData()));

        ContractCallInfo callInfo = new ContractCallInfo();
        callInfo.setCreater(AddressTool.getStringAddressByBytes(data.getSender()));
        callInfo.setContractAddress(AddressTool.getStringAddressByBytes(data.getContractAddress()));
        callInfo.setGasLimit(data.getGasLimit());
        callInfo.setPrice(data.getPrice());
        callInfo.setMethodName(data.getMethodName());
        callInfo.setMethodDesc(data.getMethodDesc());
        callInfo.setCreateTxHash(tx.getHash().toHex());
        callInfo.setValue(data.getValue());
        try {
            String args = JSONUtils.obj2json(data.getArgs());
            callInfo.setArgs(args);
        } catch (JsonProcessingException e) {
            throw new NulsException(CommonCodeConstanst.DATA_PARSE_ERROR);
        }

        //查询智能合约详情之前，先查询创建智能合约的执行结果是否成功
        if (tx.getStatus() == TxStatusEnum.CONFIRMED) {
            Result<ContractResultInfo> result = WalletRpcHandler.getContractResultInfo(chainId, callInfo.getCreateTxHash());
            callInfo.setResultInfo(result.getData());
        }

        String remark = "";
        if (tx.getRemark() != null) {
            remark = new String(tx.getRemark(), StandardCharsets.UTF_8);
        }
        if (callInfo.getResultInfo() != null && callInfo.getResultInfo().getTokenTransfers() != null) {
            for (TokenTransfer tokenTransfer : callInfo.getResultInfo().getTokenTransfers()) {
                tokenTransfer.setRemark(remark);
            }
        }
        return callInfo;
    }

    public static ContractCallInfo toContractCallInfoForCrossChain(int chainId, Transaction tx) throws NulsException {
        ContractResultInfo contractResultInfo = null;
        //查询智能合约详情之前，先查询创建智能合约的执行结果是否成功
        if (tx.getStatus() == TxStatusEnum.CONFIRMED) {
            try {
                Result<ContractResultInfo> result = WalletRpcHandler.getContractResultInfo(chainId, tx.getHash().toHex());
                if (result.getData() == null) {
                    return null;
                }
                contractResultInfo = result.getData();
            } catch (Exception e) {
                return null;
            }
        }
        if (contractResultInfo == null) {
            return null;
        }
        ContractCallInfo callInfo = new ContractCallInfo();
        callInfo.setContractAddress(contractResultInfo.getContractAddress());
        callInfo.setGasLimit(CROSS_CHAIN_GASLIMIT);
        callInfo.setPrice(CONTRACT_MINIMUM_PRICE);
        callInfo.setMethodName(CROSS_CHAIN_SYSTEM_CONTRACT_TRANSFER_IN_METHOD_NAME);
        callInfo.setValue(BigInteger.ZERO);
        callInfo.setCreateTxHash(tx.getHash().toHex());
        String nrcContractAddress = null;
        List<TokenTransfer> tokenTransfers = contractResultInfo.getTokenTransfers();
        if (tokenTransfers != null && !tokenTransfers.isEmpty()) {
            nrcContractAddress = tokenTransfers.get(0).getContractAddress();
        }
        CoinData coinData = tx.getCoinDataInstance();
        List<CoinTo> toList = coinData.getTo();
        CoinTo coinTo = toList.get(0);
        byte[] toAddress = coinTo.getAddress();
        List<CoinFrom> fromList = coinData.getFrom();
        CoinFrom from = fromList.get(0);
        byte[] fromAddress = from.getAddress();
        BigInteger amount = coinTo.getAmount();
        int assetsChainId = coinTo.getAssetsChainId();
        int assetsId = coinTo.getAssetsId();

        String[][] args = new String[][]{
                new String[]{nrcContractAddress},
                new String[]{AddressTool.getStringAddressByBytes(fromAddress)},
                new String[]{AddressTool.getStringAddressByBytes(toAddress)},
                new String[]{amount.toString()},
                new String[]{String.valueOf(assetsChainId)},
                new String[]{String.valueOf(assetsId)}};
        try {
            String argsStr = JSONUtils.obj2json(args);
            callInfo.setArgs(argsStr);
        } catch (JsonProcessingException e) {
            throw new NulsException(CommonCodeConstanst.DATA_PARSE_ERROR);
        }
        callInfo.setResultInfo(contractResultInfo);

        String remark = "";
        if (tx.getRemark() != null) {
            remark = new String(tx.getRemark(), StandardCharsets.UTF_8);
        }
        if (callInfo.getResultInfo() != null && callInfo.getResultInfo().getTokenTransfers() != null) {
            for (TokenTransfer tokenTransfer : callInfo.getResultInfo().getTokenTransfers()) {
                tokenTransfer.setRemark(remark);
            }
        }
        return callInfo;
    }

    public static ContractCallInfo toContractCallInfo(int chainId, Transaction tx, ContractResultInfo resultInfo) throws NulsException {
        CallContractData data = new CallContractData();
        data.parse(new NulsByteBuffer(tx.getTxData()));

        ContractCallInfo callInfo = new ContractCallInfo();
        callInfo.setCreater(AddressTool.getStringAddressByBytes(data.getSender()));
        callInfo.setContractAddress(AddressTool.getStringAddressByBytes(data.getContractAddress()));
        callInfo.setGasLimit(data.getGasLimit());
        callInfo.setPrice(data.getPrice());
        callInfo.setMethodName(data.getMethodName());
        callInfo.setMethodDesc(data.getMethodDesc());
        callInfo.setCreateTxHash(tx.getHash().toHex());
        callInfo.setValue(data.getValue());
        try {
            String args = JSONUtils.obj2json(data.getArgs());
            callInfo.setArgs(args);
        } catch (JsonProcessingException e) {
            throw new NulsException(CommonCodeConstanst.DATA_PARSE_ERROR);
        }
        callInfo.setResultInfo(resultInfo);
        // add by pierre at 2022/6/27 解析内部创建合约
        List<ContractInternalCreateInfo> internalCreates = resultInfo.getInternalCreates();
        if (!internalCreates.isEmpty()) {
            Map<String, ContractInfo> contractInfoMap = new HashMap<>();
            for (ContractInternalCreateInfo internalCreate : internalCreates) {
                contractInfoMap.put(internalCreate.getContractAddress(), toContractInfoByInternalCreate(chainId, tx, internalCreate, resultInfo));
            }
            callInfo.setInternalCreateContractInfos(contractInfoMap);
        }

        String remark = "";
        if (tx.getRemark() != null) {
            remark = new String(tx.getRemark(), StandardCharsets.UTF_8);
        }
        if (callInfo.getResultInfo() != null && callInfo.getResultInfo().getTokenTransfers() != null) {
            for (TokenTransfer tokenTransfer : callInfo.getResultInfo().getTokenTransfers()) {
                tokenTransfer.setRemark(remark);
            }
        }
        return callInfo;
    }

    private static ContractInfo toContractInfoByInternalCreate(int chainId, Transaction tx, ContractInternalCreateInfo internalCreate, ContractResultInfo resultInfo) throws NulsException {
        ContractInfo contractInfo = new ContractInfo();
        contractInfo.setCreateTxHash(tx.getHash().toHex());
        contractInfo.setAlias("internal_create");
        contractInfo.setContractAddress(internalCreate.getContractAddress());
        contractInfo.setBlockHeight(tx.getBlockHeight());
        contractInfo.setCreateTime(tx.getTime());
        contractInfo.setArgs(internalCreate.getArgs());
        contractInfo.setResultInfo(resultInfo);
        if (!resultInfo.isSuccess()) {
            contractInfo.setSuccess(false);
            contractInfo.setStatus(ApiConstant.CONTRACT_STATUS_FAIL);
            contractInfo.setErrorMsg(resultInfo.getErrorMessage());
            return contractInfo;
        }
        contractInfo.setStatus(ApiConstant.CONTRACT_STATUS_NORMAL);
        contractInfo.setSuccess(true);
        fillContractInfo(chainId, contractInfo);
        return contractInfo;
    }

    public static void fillContractInfo(int chainId, ContractInfo contractInfo) throws NulsException {
        Map<String, Object> params = new HashMap<>();
        params.put(Constants.CHAIN_ID, chainId);
        params.put("contractAddress", contractInfo.getContractAddress());
        Map map = (Map) RpcCall.request(ModuleE.SC.abbr, CommandConstant.CONTRACT_INFO, params);

        contractInfo.setCreater(map.get("creater").toString());
        contractInfo.setNrc20((Boolean) map.get("nrc20"));
        contractInfo.setTokenType((Integer) map.get("tokenType"));
        contractInfo.setDirectPayable((Boolean) map.get("directPayable"));
        Boolean directPayableByOtherAsset = (Boolean) map.get("directPayableByOtherAsset");
        if (directPayableByOtherAsset != null) {
            contractInfo.setDirectPayableByOtherAsset(directPayableByOtherAsset);
        }
        // nrc1155数据
        boolean isNrc1155 = contractInfo.getTokenType() == TOKEN_TYPE_NRC1155;
        if (isNrc1155) {
            contractInfo.setUri((String) map.get("tokenUri"));
        }
        boolean isNrc721 = contractInfo.getTokenType() == TOKEN_TYPE_NRC721;
        if (isNrc721 || isNrc1155) {
            Object tokenName = map.get("nrc20TokenName");
            tokenName = tokenName == null ? EMPTY_STRING : tokenName;
            Object tokenSymbol = map.get("nrc20TokenSymbol");
            tokenSymbol = tokenSymbol == null ? EMPTY_STRING : tokenSymbol;
            contractInfo.setTokenName(tokenName.toString());
            contractInfo.setSymbol(tokenSymbol.toString());
            contractInfo.setOwners(new ArrayList<>());
        }
        if (contractInfo.isNrc20()) {
            contractInfo.setTokenName(map.get("nrc20TokenName").toString());
            contractInfo.setSymbol(map.get("nrc20TokenSymbol").toString());
            contractInfo.setDecimals((Integer) map.get("decimals"));
            contractInfo.setTotalSupply(map.get("totalSupply").toString());
            contractInfo.setOwners(new ArrayList<>());
        }

        List<Map<String, Object>> methodMap = (List<Map<String, Object>>) map.get("method");
        List<ContractMethod> methodList = new ArrayList<>();
        List<Map<String, Object>> argsList;
        List<ContractMethodArg> paramList;
        for (Map<String, Object> map1 : methodMap) {
            ContractMethod method = new ContractMethod();
            method.setName((String) map1.get("name"));
            method.setDesc((String) map1.get("desc"));
            method.setReturnType((String) map1.get("returnArg"));
            method.setView((boolean) map1.get("view"));
            method.setPayable((boolean) map1.get("payable"));
            Boolean payableMultyAsset = (Boolean) map1.get("payableMultyAsset");
            if (payableMultyAsset != null) {
                method.setPayableMultyAsset(payableMultyAsset);
            }
            method.setEvent((boolean) map1.get("event"));
            method.setJsonSerializable((boolean) map1.get("jsonSerializable"));
            argsList = (List<Map<String, Object>>) map1.get("args");
            paramList = new ArrayList<>();
            for (Map<String, Object> arg : argsList) {
                paramList.add(makeContractMethodArg(arg));
            }
            method.setParams(paramList);
            methodList.add(method);
        }
        contractInfo.setMethods(methodList);
    }

    public static ContractCallInfo toContractCallInfoForCrossChain(int chainId, Transaction tx, ContractResultInfo resultInfo) throws NulsException {
        if (resultInfo == null) {
            return null;
        }
        ContractCallInfo callInfo = new ContractCallInfo();
        callInfo.setContractAddress(resultInfo.getContractAddress());
        callInfo.setGasLimit(CROSS_CHAIN_GASLIMIT);
        callInfo.setPrice(CONTRACT_MINIMUM_PRICE);
        callInfo.setMethodName(CROSS_CHAIN_SYSTEM_CONTRACT_TRANSFER_IN_METHOD_NAME);
        callInfo.setValue(BigInteger.ZERO);
        callInfo.setCreateTxHash(tx.getHash().toHex());
        String nrcContractAddress = null;
        List<TokenTransfer> tokenTransfers = resultInfo.getTokenTransfers();
        if (tokenTransfers != null && !tokenTransfers.isEmpty()) {
            nrcContractAddress = tokenTransfers.get(0).getContractAddress();
        }
        CoinData coinData = tx.getCoinDataInstance();
        List<CoinTo> toList = coinData.getTo();
        CoinTo coinTo = toList.get(0);
        byte[] toAddress = coinTo.getAddress();
        List<CoinFrom> fromList = coinData.getFrom();
        CoinFrom from = fromList.get(0);
        byte[] fromAddress = from.getAddress();
        BigInteger amount = coinTo.getAmount();
        int assetsChainId = coinTo.getAssetsChainId();
        int assetsId = coinTo.getAssetsId();

        String[][] args = new String[][]{
                new String[]{nrcContractAddress},
                new String[]{AddressTool.getStringAddressByBytes(fromAddress)},
                new String[]{AddressTool.getStringAddressByBytes(toAddress)},
                new String[]{amount.toString()},
                new String[]{String.valueOf(assetsChainId)},
                new String[]{String.valueOf(assetsId)}};
        try {
            String argsStr = JSONUtils.obj2json(args);
            callInfo.setArgs(argsStr);
        } catch (JsonProcessingException e) {
            throw new NulsException(CommonCodeConstanst.DATA_PARSE_ERROR);
        }
        callInfo.setResultInfo(resultInfo);
        return callInfo;
    }

    public static ContractDeleteInfo toContractDeleteInfo(int chainId, Transaction tx) throws NulsException {
        DeleteContractData data = new DeleteContractData();
        data.parse(new NulsByteBuffer(tx.getTxData()));

        ContractDeleteInfo info = new ContractDeleteInfo();
        info.setTxHash(tx.getHash().toHex());
        info.setCreater(AddressTool.getStringAddressByBytes(data.getSender()));
        info.setContractAddress(AddressTool.getStringAddressByBytes(data.getContractAddress()));
        if (tx.getStatus() == TxStatusEnum.CONFIRMED) {
            Result<ContractResultInfo> result = WalletRpcHandler.getContractResultInfo(chainId, info.getTxHash());
            info.setResultInfo(result.getData());
        }

        return info;
    }

    public static ContractDeleteInfo toContractDeleteInfo(int chainId, Transaction tx, ContractResultInfo resultInfo) throws NulsException {
        DeleteContractData data = new DeleteContractData();
        data.parse(new NulsByteBuffer(tx.getTxData()));

        ContractDeleteInfo info = new ContractDeleteInfo();
        info.setTxHash(tx.getHash().toHex());
        info.setCreater(AddressTool.getStringAddressByBytes(data.getSender()));
        info.setContractAddress(AddressTool.getStringAddressByBytes(data.getContractAddress()));
        info.setResultInfo(resultInfo);
        return info;
    }

    public static ContractResultInfo toContractResultInfo(int chainId, String hash, Map<String, Object> resultMap) throws NulsException {
        ContractResultInfo resultInfo = new ContractResultInfo();
        resultInfo.setTxHash(hash);
        resultInfo.setSuccess((Boolean) resultMap.get("success"));
        resultInfo.setContractAddress((String) resultMap.get("contractAddress"));
        resultInfo.setErrorMessage((String) resultMap.get("errorMessage"));
        resultInfo.setResult((String) resultMap.get("result"));

        resultInfo.setGasUsed(resultMap.get("gasUsed") != null ? Long.parseLong(resultMap.get("gasUsed").toString()) : 0);
        resultInfo.setGasLimit(resultMap.get("gasLimit") != null ? Long.parseLong(resultMap.get("gasLimit").toString()) : 0);
        resultInfo.setPrice(resultMap.get("price") != null ? Long.parseLong(resultMap.get("price").toString()) : 0);
        resultInfo.setTotalFee((String) resultMap.get("totalFee"));
        resultInfo.setTxSizeFee((String) resultMap.get("txSizeFee"));
        resultInfo.setActualContractFee((String) resultMap.get("actualContractFee"));
        resultInfo.setRefundFee((String) resultMap.get("refundFee"));
        resultInfo.setValue((String) resultMap.get("value"));
        //resultInfo.setBalance((String) map.get("balance"));
        resultInfo.setEvents((List<String>) resultMap.get("events"));
        resultInfo.setRemark((String) resultMap.get("remark"));
        resultInfo.setContractTxList((List<String>) resultMap.get("contractTxList"));

        List<Map<String, Object>> transfers = (List<Map<String, Object>>) resultMap.get("transfers");
        List<NulsTransfer> transferList = new ArrayList<>();
        for (Map map1 : transfers) {
            NulsTransfer nulsTransfer = new NulsTransfer();
            nulsTransfer.setTxHash((String) map1.get("txHash"));
            nulsTransfer.setFrom((String) map1.get("from"));
            nulsTransfer.setValue((String) map1.get("value"));
            nulsTransfer.setOutputs((List<Map<String, Object>>) map1.get("outputs"));
            transferList.add(nulsTransfer);
        }
        resultInfo.setNulsTransfers(transferList);

        transfers = (List<Map<String, Object>>) resultMap.get("tokenTransfers");
        List<TokenTransfer> tokenTransferList = new ArrayList<>();
        for (Map map1 : transfers) {
            TokenTransfer tokenTransfer = new TokenTransfer();
            tokenTransfer.setContractAddress((String) map1.get("contractAddress"));
            tokenTransfer.setFromAddress((String) map1.get("from"));
            tokenTransfer.setToAddress((String) map1.get("to"));
            tokenTransfer.setValue((String) map1.get("value"));
            tokenTransfer.setName((String) map1.get("name"));
            tokenTransfer.setSymbol((String) map1.get("symbol"));
            tokenTransfer.setDecimals((Integer) map1.get("decimals"));
            tokenTransferList.add(tokenTransfer);
        }
        resultInfo.setTokenTransfers(tokenTransferList);

        // nrc721
        transfers = (List<Map<String, Object>>) resultMap.get("token721Transfers");
        List<Token721Transfer> token721TransferList = new ArrayList<>();
        for (Map map1 : transfers) {
            Token721Transfer token721Transfer = new Token721Transfer();
            token721Transfer.setContractAddress((String) map1.get("contractAddress"));
            token721Transfer.setFromAddress((String) map1.get("from"));
            token721Transfer.setToAddress((String) map1.get("to"));
            token721Transfer.setTokenId((String) map1.get("tokenId"));
            token721Transfer.setName((String) map1.get("name"));
            token721Transfer.setSymbol((String) map1.get("symbol"));
            token721TransferList.add(token721Transfer);
        }
        resultInfo.setToken721Transfers(token721TransferList);

        // nrc1155
        transfers = (List<Map<String, Object>>) resultMap.get("token1155Transfers");
        List<Token1155Transfer> token1155TransferList = new ArrayList<>();
        for (Map map1 : transfers) {
            List<String> ids = (List<String>) map1.get("ids");
            List<String> values = (List<String>) map1.get("values");
            for (int i = 0; i < ids.size(); i++) {
                String id = ids.get(i);
                String value = values.get(i);
                Token1155Transfer token1155Transfer = new Token1155Transfer();
                token1155Transfer.setContractAddress((String) map1.get("contractAddress"));
                token1155Transfer.setOperatorAddress((String) map1.get("operator"));
                token1155Transfer.setFromAddress((String) map1.get("from"));
                token1155Transfer.setToAddress((String) map1.get("to"));
                token1155Transfer.setTokenId(id);
                token1155Transfer.setValue(value);
                token1155Transfer.setName((String) map1.get("name"));
                token1155Transfer.setSymbol((String) map1.get("symbol"));
                token1155TransferList.add(token1155Transfer);
            }
        }
        resultInfo.setToken1155Transfers(token1155TransferList);

        List<Map<String, Object>> internalCreates = (List<Map<String, Object>>) resultMap.get("internalCreates");
        List<ContractInternalCreateInfo> internalCreateList = new ArrayList<>();
        for (Map map1 : internalCreates) {
            ContractInternalCreateInfo internalCreate = new ContractInternalCreateInfo();
            internalCreate.setSender((String) map1.get("sender"));
            internalCreate.setContractAddress((String) map1.get("contractAddress"));
            internalCreate.setCodeCopyBy((String) map1.get("codeCopyBy"));
            internalCreate.setArgs((String) map1.get("args"));
            internalCreateList.add(internalCreate);
        }
        resultInfo.setInternalCreates(internalCreateList);

        List<CrossAssetTransfer> crossAssetTransferList = new ArrayList<>();
        List<String> contractTxList = resultInfo.getContractTxList();
        for (String contractTxHex : contractTxList) {
            Transaction tx = new Transaction();
            tx.parse(HexUtil.decode(contractTxHex), 0);
            CoinData coinData = tx.getCoinDataInstance();
            List<CoinFrom> froms = coinData.getFrom();
            CoinFrom from = froms.get(0);
            if (from.getAssetsChainId() == chainId && from.getAssetsId() == 1) {
                continue;
            }
            CoinTo coinTo = coinData.getTo().get(0);
            crossAssetTransferList.add(new CrossAssetTransfer(
                AddressTool.getStringAddressByBytes(from.getAddress()),
                AddressTool.getStringAddressByBytes(coinTo.getAddress()),
                coinTo.getAmount().toString(),
                coinTo.getAssetsChainId(),
                coinTo.getAssetsId(),
                coinTo.getLockTime()
            ));
        }
        resultInfo.setCrossAssetTransfers(crossAssetTransferList);
        return resultInfo;
    }

    private static ContractTransferInfo toContractTransferInfo(Transaction tx) throws NulsException {
        ContractTransferData data = new ContractTransferData();
        data.parse(new NulsByteBuffer(tx.getTxData()));

        ContractTransferInfo info = new ContractTransferInfo();
        info.setTxHash(tx.getHash().toHex());
        info.setContractAddress(AddressTool.getStringAddressByBytes(data.getContractAddress()));
        info.setOrginTxHash(data.getOrginTxHash().toHex());
        return info;
    }

    private static ChainInfo toChainInfo(Transaction tx, int version) throws NulsException {
        ChainInfo chainInfo = new ChainInfo();
        if (version < 4) {
            TxChain txChain = new TxChain();
            txChain.parse(new NulsByteBuffer(tx.getTxData()));
            chainInfo.setChainId(txChain.getDefaultAsset().getChainId());

            AssetInfo assetInfo = new AssetInfo();
            TxAsset txAsset = txChain.getDefaultAsset();
            assetInfo.setAssetId(txAsset.getAssetId());
            assetInfo.setChainId(txAsset.getChainId());
            assetInfo.setSymbol(txAsset.getSymbol());
            assetInfo.setInitCoins(txAsset.getInitNumber());
            assetInfo.setDecimals(txAsset.getDecimalPlaces());
            chainInfo.setDefaultAsset(assetInfo);
            chainInfo.getAssets().add(assetInfo);
        } else if (version == 4) {
            io.nuls.api.model.entity.v4.TxChain txChain = new io.nuls.api.model.entity.v4.TxChain();
            txChain.parse(new NulsByteBuffer(tx.getTxData()));
            chainInfo.setChainId(txChain.getDefaultAsset().getChainId());

            AssetInfo assetInfo = new AssetInfo();
            io.nuls.api.model.entity.v4.TxAsset txAsset = txChain.getDefaultAsset();
            assetInfo.setAssetId(txAsset.getAssetId());
            assetInfo.setChainId(txAsset.getChainId());
            assetInfo.setSymbol(txAsset.getSymbol());
            assetInfo.setInitCoins(txAsset.getInitNumber());
            assetInfo.setDecimals(txAsset.getDecimalPlaces());
            chainInfo.setDefaultAsset(assetInfo);
            chainInfo.getAssets().add(assetInfo);
        } else {
            io.nuls.api.model.entity.v5.TxChain txChain = new io.nuls.api.model.entity.v5.TxChain();
            txChain.parse(tx.getTxData(), 0);
            chainInfo.setChainId(txChain.getDefaultAsset().getChainId());
            chainInfo.setChainName(txChain.getName());

            AssetInfo assetInfo = new AssetInfo();
            io.nuls.api.model.entity.v5.TxAsset txAsset = txChain.getDefaultAsset();
            assetInfo.setAssetId(txAsset.getAssetId());
            assetInfo.setChainId(txAsset.getChainId());
            assetInfo.setSymbol(txAsset.getSymbol());
            assetInfo.setInitCoins(txAsset.getInitNumber());
            assetInfo.setDecimals(txAsset.getDecimalPlaces());
            chainInfo.setDefaultAsset(assetInfo);
            chainInfo.getAssets().add(assetInfo);
        }
        return chainInfo;
    }

    private static AssetInfo toAssetInfo(Transaction tx, int version) throws NulsException {
        AssetInfo assetInfo = new AssetInfo();
        if (version >= 4) {
            io.nuls.api.model.entity.v4.TxAsset txAsset = new io.nuls.api.model.entity.v4.TxAsset();
            txAsset.parse(new NulsByteBuffer(tx.getTxData()));

            assetInfo.setAssetId(txAsset.getAssetId());
            assetInfo.setChainId(txAsset.getChainId());
            assetInfo.setSymbol(txAsset.getSymbol());
            assetInfo.setInitCoins(txAsset.getInitNumber());
            assetInfo.setDecimals(txAsset.getDecimalPlaces());
            assetInfo.setAddress("");
        } else {
            TxAsset txAsset = new TxAsset();
            txAsset.parse(new NulsByteBuffer(tx.getTxData()));

            assetInfo.setAssetId(txAsset.getAssetId());
            assetInfo.setChainId(txAsset.getChainId());
            assetInfo.setSymbol(txAsset.getSymbol());
            assetInfo.setInitCoins(txAsset.getInitNumber());
            assetInfo.setDecimals(txAsset.getDecimalPlaces());
            assetInfo.setAddress(AddressTool.getStringAddressByBytes(txAsset.getAddress()));
        }

        return assetInfo;
    }

    public static BigInteger calcCoinBaseReward(int chainId, TransactionInfo coinBaseTx) {
        BigInteger reward = BigInteger.ZERO;
        if (coinBaseTx.getCoinTos() == null) {
            return reward;
        }
        //奖励只计算本链的共识资产
        AssetInfo assetInfo = CacheManager.getCacheChain(chainId).getDefaultAsset();
        for (CoinToInfo coinTo : coinBaseTx.getCoinTos()) {
            if (coinTo.getChainId() == assetInfo.getChainId() || coinTo.getAssetsId() == assetInfo.getAssetId()) {
                reward = reward.add(coinTo.getAmount());
            }
        }
        return reward;
    }

    public static BigInteger calcFee(List<TransactionInfo> txs, int chainId) {
        BigInteger fee = BigInteger.ZERO;
        //手续费只计算本链的共识资产
        AssetInfo assetInfo = CacheManager.getCacheChain(chainId).getDefaultAsset();
        for (int i = 1; i < txs.size(); i++) {
            FeeInfo feeInfo = txs.get(i).getFee();
            if (feeInfo.getChainId() == assetInfo.getChainId() && feeInfo.getAssetId() == assetInfo.getAssetId()) {
                fee = fee.add(feeInfo.getValue());
            }
        }
        return fee;
    }
}
