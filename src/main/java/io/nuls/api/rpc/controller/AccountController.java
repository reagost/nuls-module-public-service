/*
 * MIT License
 * Copyright (c) 2017-2019 nuls.io
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.nuls.api.rpc.controller;

import io.nuls.api.ApiContext;
import io.nuls.api.analysis.WalletRpcHandler;
import io.nuls.api.cache.ApiCache;
import io.nuls.api.constant.config.ApiConfig;
import io.nuls.api.db.*;
import io.nuls.api.manager.CacheManager;
import io.nuls.api.model.po.*;
import io.nuls.api.model.po.mini.MiniAccountInfo;
import io.nuls.api.model.rpc.*;
import io.nuls.api.utils.LoggerUtil;
import io.nuls.api.utils.VerifyUtils;
import io.nuls.base.basic.AddressTool;
import io.nuls.core.basic.Result;
import io.nuls.core.core.annotation.Autowired;
import io.nuls.core.core.annotation.Controller;
import io.nuls.core.core.annotation.RpcMethod;
import io.nuls.core.model.StringUtils;
import io.nuls.core.parse.MapUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author Niels
 */
@Controller
public class AccountController {
    @Autowired
    private ApiConfig apiConfig;
    @Autowired
    private AccountService accountService;
    @Autowired
    private BlockService blockHeaderService;
    @Autowired
    private ChainService chainService;
    @Autowired
    private AccountLedgerService accountLedgerService;
    @Autowired
    private AliasService aliasService;

    @Autowired
    TokenService tokenService;

    @RpcMethod("getActiveAddressData")
    public RpcResult getActiveAddressData(List<Object> params) {
        VerifyUtils.verifyParams(params, 1);
        int   pageSize;
        try {
            pageSize = (int) params.get(0);
        } catch (Exception e) {
            return RpcResult.paramError("[pageSize] is inValid");
        }
        RpcResult result = new RpcResult();
        result.setResult(this.accountService.getActiveAddressData(pageSize));
        return result;
    }
    @RpcMethod("getAccountList")
    public RpcResult getAccountList(List<Object> params) {
        VerifyUtils.verifyParams(params, 3);
        int chainId, pageNumber, pageSize;
        try {
            chainId = (int) params.get(0);
        } catch (Exception e) {
            return RpcResult.paramError("[chainId] is inValid");
        }
        try {
            pageNumber = (int) params.get(1);
        } catch (Exception e) {
            return RpcResult.paramError("[pageNumber] is inValid");
        }
        try {
            pageSize = (int) params.get(2);
        } catch (Exception e) {
            return RpcResult.paramError("[pageSize] is inValid");
        }

        if (pageNumber <= 0) {
            pageNumber = 1;
        }
        if (pageSize <= 0 || pageSize > 100) {
            pageSize = 10;
        }
        RpcResult result = new RpcResult();
        PageInfo<AccountInfo> pageInfo;
        if (CacheManager.isChainExist(chainId)) {
            pageInfo = accountService.pageQuery(chainId, pageNumber, pageSize);
        } else {
            pageInfo = new PageInfo<>(pageNumber, pageSize);
        }
        result.setResult(pageInfo);
        return result;

    }

    @RpcMethod("getAccountTxs")
    public RpcResult getAccountTxs(List<Object> params) {
        VerifyUtils.verifyParams(params, 7);
        int chainId, assetChainId, assetId, pageNumber, pageSize, type;
        String address;
        long startHeight, endHeight;
        try {
            chainId = (int) params.get(0);
        } catch (Exception e) {
            return RpcResult.paramError("[chainId] is inValid");
        }
        try {
            pageNumber = (int) params.get(1);
        } catch (Exception e) {
            return RpcResult.paramError("[pageNumber] is inValid");
        }
        try {
            pageSize = (int) params.get(2);
        } catch (Exception e) {
            return RpcResult.paramError("[pageSize] is inValid");
        }
        try {
            address = (String) params.get(3);
        } catch (Exception e) {
            return RpcResult.paramError("[address] is inValid");
        }
        try {
            type = (int) params.get(4);
        } catch (Exception e) {
            return RpcResult.paramError("[type] is inValid");
        }
        try {
            startHeight = Long.parseLong("" + params.get(5));
        } catch (Exception e) {
            return RpcResult.paramError("[startHeight] is invalid");
        }
        try {
            endHeight = Long.parseLong("" + params.get(6));
        } catch (Exception e) {
            return RpcResult.paramError("[endHeight] is invalid");
        }
        try {
            assetChainId = (int) params.get(7);
        } catch (Exception e) {
            return RpcResult.paramError("[assetChainId] is invalid");
        }
        try {
            assetId = (int) params.get(8);
        } catch (Exception e) {
            return RpcResult.paramError("[assetId] is invalid");
        }
        if (!AddressTool.validAddress(chainId, address)) {
            return RpcResult.paramError("[address] is inValid");
        }
        if (pageNumber <= 0) {
            pageNumber = 1;
        }
        if (pageSize <= 0 || pageSize > 100) {
            pageSize = 10;
        }
        RpcResult result = new RpcResult();
        try {
            PageInfo<TxRelationInfo> pageInfo;
            if (CacheManager.isChainExist(chainId)) {
                pageInfo = accountService.getAccountTxs(chainId, address, pageNumber, pageSize, type, startHeight, endHeight, assetChainId, assetId);
                result.setResult(new PageInfo<>(pageNumber, pageSize, pageInfo.getTotalCount(), pageInfo.getList().stream().map(d -> {
                    Map res = MapUtils.beanToMap(d);
                    AssetInfo assetInfo = CacheManager.getAssetInfoMap().get(d.getChainId() + "-" + d.getAssetId());
                    if (assetInfo != null) {
                        res.put("symbol", assetInfo.getSymbol());
                        res.put("decimals", assetInfo.getDecimals());
                    }
                    return res;
                }).collect(Collectors.toList())));
            } else {
                result.setResult(new PageInfo<>(pageNumber, pageSize));
            }
        } catch (Exception e) {
            LoggerUtil.commonLog.error(e);
        }
        return result;
    }

    /**
     * 查询账户普通转账和跨链转账交易
     *
     * @param params
     * @return
     */
    @RpcMethod("queryAccountTxs")
    public RpcResult queryAccountTxs(List<Object> params) {
        VerifyUtils.verifyParams(params, 5);
        int chainId, assetChainId, assetId, pageNumber;
        String address;
        try {
            chainId = (int) params.get(0);
        } catch (Exception e) {
            return RpcResult.paramError("[chainId] is inValid");
        }
        try {
            pageNumber = (int) params.get(1);
        } catch (Exception e) {
            return RpcResult.paramError("[pageNumber] is inValid");
        }
        try {
            address = (String) params.get(2);
        } catch (Exception e) {
            return RpcResult.paramError("[address] is inValid");
        }
        try {
            assetChainId = (int) params.get(3);
        } catch (Exception e) {
            return RpcResult.paramError("[assetChainId] is invalid");
        }
        try {
            assetId = (int) params.get(4);
        } catch (Exception e) {
            return RpcResult.paramError("[assetId] is invalid");
        }
        if (!AddressTool.validAddress(chainId, address)) {
            return RpcResult.paramError("[address] is inValid");
        }
        if (pageNumber <= 0) {
            pageNumber = 1;
        }
        RpcResult result = new RpcResult();
        try {
            PageInfo<TxRelationInfo> pageInfo = accountService.queryAccountTxs(chainId, address, pageNumber, assetChainId, assetId);
            result.setResult(new PageInfo<>(pageNumber, 10, pageInfo.getTotalCount(), pageInfo.getList().stream().map(d -> {
                Map res = MapUtils.beanToMap(d);
                AssetInfo assetInfo = CacheManager.getAssetInfoMap().get(d.getChainId() + "-" + d.getAssetId());
                if (assetInfo != null) {
                    res.put("symbol", assetInfo.getSymbol());
                    res.put("decimals", assetInfo.getDecimals());
                }
                Result<TransactionInfo> txResult = WalletRpcHandler.getTx(chainId, d.getTxHash());
                TransactionInfo tx = txResult.getData();

                boolean has = false;
                for (CoinFromInfo fromInfo : tx.getCoinFroms()) {
                    if (fromInfo.getChainId() == assetChainId && fromInfo.getAssetsId() == assetId) {
                        res.put("from", fromInfo.getAddress());
                        has = true;
                        break;
                    }
                }
                if (!has) {
                    res.put("from", tx.getCoinFroms().get(0).getAddress());
                }

                has = false;
                for (CoinToInfo toInfo : tx.getCoinTos()) {
                    if (toInfo.getChainId() == assetChainId && toInfo.getAssetsId() == assetId) {
                        res.put("to", toInfo.getAddress());
                        has = true;
                        break;
                    }
                }
                if (!has) {
                    res.put("to", tx.getCoinTos().get(0).getAddress());
                }
                res.put("remark", tx.getRemark());
                return res;
            }).collect(Collectors.toList())));
        } catch (Exception e) {
            LoggerUtil.commonLog.error(e);
        }
        return result;
    }

    @RpcMethod("getAcctTxs")
    public RpcResult getAcctTxs(List<Object> params) {
        VerifyUtils.verifyParams(params, 7);
        int chainId, assetChainId, assetId, pageNumber, pageSize, type;
        String address;
        long startTime, endTime;
        try {
            chainId = (int) params.get(0);
        } catch (Exception e) {
            return RpcResult.paramError("[chainId] is inValid");
        }
        try {
            address = (String) params.get(1);
        } catch (Exception e) {
            return RpcResult.paramError("[address] is inValid");
        }
        try {
            type = (int) params.get(2);
        } catch (Exception e) {
            return RpcResult.paramError("[type] is inValid");
        }
        try {
            assetChainId = (int) params.get(3);
        } catch (Exception e) {
            return RpcResult.paramError("[assetChainId] is inValid");
        }
        try {
            assetId = (int) params.get(4);
        } catch (Exception e) {
            return RpcResult.paramError("[assetId] is inValid");
        }
        try {
            startTime = Long.parseLong("" + params.get(5));
        } catch (Exception e) {
            return RpcResult.paramError("[startTime] is invalid");
        }
        try {
            endTime = Long.parseLong("" + params.get(6));
        } catch (Exception e) {
            return RpcResult.paramError("[endTime] is invalid");
        }

        try {
            pageNumber = (int) params.get(7);
        } catch (Exception e) {
            return RpcResult.paramError("[pageNumber] is inValid");
        }
        try {
            pageSize = (int) params.get(8);
        } catch (Exception e) {
            return RpcResult.paramError("[pageSize] is inValid");
        }

        if (!AddressTool.validAddress(chainId, address)) {
            return RpcResult.paramError("[address] is inValid");
        }
        if (pageNumber <= 0) {
            pageNumber = 1;
        }
        if (pageSize <= 0 || pageSize > 100) {
            pageSize = 10;
        }

        RpcResult result = new RpcResult();
        try {
            PageInfo<TxRelationInfo> pageInfo;
            if (CacheManager.isChainExist(chainId)) {
                pageInfo = accountService.getAcctTxs(chainId, assetChainId, assetId, address, type, startTime, endTime, pageNumber, pageSize);
                result.setResult(new PageInfo<>(pageNumber, pageSize, pageInfo.getTotalCount(), pageInfo.getList().stream().map(d -> {
                    Map res = MapUtils.beanToMap(d);
                    AssetInfo assetInfo = CacheManager.getAssetInfoMap().get(d.getChainId() + "-" + d.getAssetId());
                    if (assetInfo != null) {
                        res.put("symbol", assetInfo.getSymbol());
                        res.put("decimals", assetInfo.getDecimals());
                    }
                    return res;
                }).collect(Collectors.toList())));
            } else {
                result.setResult(new PageInfo<>(pageNumber, pageSize));
            }
        } catch (Exception e) {
            LoggerUtil.commonLog.error(e);
        }
        return result;
    }

    @RpcMethod("getAccount")
    public RpcResult getAccount(List<Object> params) {
        VerifyUtils.verifyParams(params, 2);
        int chainId;
        String address;
        try {
            chainId = (int) params.get(0);
        } catch (Exception e) {
            return RpcResult.paramError("[chainId] is inValid");
        }
        try {
            address = (String) params.get(1);
        } catch (Exception e) {
            return RpcResult.paramError("[address] is inValid");
        }
        if (!AddressTool.validAddress(chainId, address)) {
            return RpcResult.paramError("[address] is inValid");
        }

        RpcResult result = new RpcResult();
        ApiCache apiCache = CacheManager.getCache(chainId);
        if (apiCache == null) {
            return RpcResult.dataNotFound();
        }
        AccountInfo accountInfo = accountService.getAccountInfo(chainId, address);
        if (accountInfo == null) {
            accountInfo = new AccountInfo(address);
        } else {
            AssetInfo defaultAsset = apiCache.getChainInfo().getDefaultAsset();
            BalanceInfo balanceInfo = WalletRpcHandler.getAccountBalance(chainId, address, defaultAsset.getChainId(), defaultAsset.getAssetId());
            accountInfo.setBalance(balanceInfo.getBalance());
            // accountInfo.setConsensusLock(balanceInfo.getConsensusLock());
            accountInfo.setTimeLock(balanceInfo.getTimeLock());
        }
        accountInfo.setSymbol(ApiContext.defaultSymbol);
        return result.setResult(accountInfo);
    }

    @RpcMethod("getAccountByAlias")
    public RpcResult getAccountByAlias(List<Object> params) {
        VerifyUtils.verifyParams(params, 2);
        int chainId;
        String alias;
        try {
            chainId = (int) params.get(0);
        } catch (Exception e) {
            return RpcResult.paramError("[chainId] is inValid");
        }
        try {
            alias = (String) params.get(1);
        } catch (Exception e) {
            return RpcResult.paramError("[alias] is inValid");
        }
        RpcResult result = new RpcResult();
        ApiCache apiCache = CacheManager.getCache(chainId);
        if (apiCache == null) {
            return RpcResult.dataNotFound();
        }
        AliasInfo aliasInfo = aliasService.getByAlias(chainId, alias);
        if (aliasInfo == null) {
            return RpcResult.dataNotFound();
        }
        AccountInfo accountInfo = accountService.getAccountInfo(chainId, aliasInfo.getAddress());
        if (accountInfo == null) {
            return RpcResult.dataNotFound();
        } else {
            AssetInfo defaultAsset = apiCache.getChainInfo().getDefaultAsset();
            BalanceInfo balanceInfo = WalletRpcHandler.getAccountBalance(chainId, aliasInfo.getAddress(), defaultAsset.getChainId(), defaultAsset.getAssetId());
            accountInfo.setBalance(balanceInfo.getBalance());
//            accountInfo.setConsensusLock(balanceInfo.getConsensusLock());
            accountInfo.setTimeLock(balanceInfo.getTimeLock());
        }
        accountInfo.setSymbol(ApiContext.defaultSymbol);
        return result.setResult(accountInfo);

    }

    @RpcMethod("getCoinRanking")
    public RpcResult getCoinRanking(List<Object> params) {
        VerifyUtils.verifyParams(params, 3);
        int chainId, pageNumber, pageSize;
        try {
            chainId = (int) params.get(0);
        } catch (Exception e) {
            return RpcResult.paramError("[chainId] is inValid");
        }
        try {
            pageNumber = (int) params.get(1);
        } catch (Exception e) {
            return RpcResult.paramError("[pageNumber] is inValid");
        }
        try {
            pageSize = (int) params.get(2);
        } catch (Exception e) {
            return RpcResult.paramError("[pageSize] is inValid");
        }

        if (pageNumber <= 0) {
            pageNumber = 1;
        }
        if (pageSize <= 0 || pageSize > 100) {
            pageSize = 10;
        }

        PageInfo<MiniAccountInfo> pageInfo;
        if (CacheManager.isChainExist(chainId)) {
            pageInfo = accountService.getCoinRanking(pageNumber, pageSize, chainId);
        } else {
            pageInfo = new PageInfo<>(pageNumber, pageSize);
        }
        return new RpcResult().setResult(pageInfo);
    }


    @RpcMethod("getAssetRanking")
    public RpcResult getAssetRanking(List<Object> params) {
        VerifyUtils.verifyParams(params, 5);
        int chainId, assetChainId, assetId, pageNumber, pageSize;
        try {
            chainId = (int) params.get(0);
        } catch (Exception e) {
            return RpcResult.paramError("[chainId] is inValid");
        }
        try {
            assetChainId = (int) params.get(1);
        } catch (Exception e) {
            return RpcResult.paramError("[assetChainId] is inValid");
        }
        try {
            assetId = (int) params.get(2);
        } catch (Exception e) {
            return RpcResult.paramError("[assetId] is inValid");
        }

        try {
            pageNumber = (int) params.get(3);
        } catch (Exception e) {
            return RpcResult.paramError("[pageNumber] is inValid");
        }
        try {
            pageSize = (int) params.get(4);
        } catch (Exception e) {
            return RpcResult.paramError("[pageSize] is inValid");
        }

        if (pageNumber <= 0) {
            pageNumber = 1;
        }
        if (pageSize <= 0 || pageSize > 100) {
            pageSize = 10;
        }

        PageInfo<MiniAccountInfo> pageInfo;
        if (CacheManager.isChainExist(chainId)) {
            if (chainId == apiConfig.getChainId() && assetChainId == apiConfig.getChainId() && assetId == apiConfig.getAssetId()
                    && pageNumber == 1 && pageSize == 15) {
                pageInfo = ApiContext.miniAccountPageInfo;
            } else {
                pageInfo = accountLedgerService.getAssetRanking(chainId, assetChainId, assetId, pageNumber, pageSize);
            }
        } else {
            pageInfo = new PageInfo<>(pageNumber, pageSize);
        }
        return new RpcResult().setResult(pageInfo);
    }


    @RpcMethod("getAccountFreezes")
    public RpcResult getAccountFreezes(List<Object> params) {
        VerifyUtils.verifyParams(params, 6);
        int chainId, assetChainId, assetId, pageNumber, pageSize;
        String address;
        try {
            chainId = (int) params.get(0);
        } catch (Exception e) {
            return RpcResult.paramError("[chainId] is inValid");
        }
        try {
            assetChainId = (int) params.get(1);
        } catch (Exception e) {
            return RpcResult.paramError("[assetChainId] is inValid");
        }
        try {
            assetId = (int) params.get(2);
        } catch (Exception e) {
            return RpcResult.paramError("[assetChainId] is inValid");
        }
        try {
            address = (String) params.get(3);
        } catch (Exception e) {
            return RpcResult.paramError("[address] is inValid");
        }
        try {
            pageNumber = (int) params.get(4);
        } catch (Exception e) {
            return RpcResult.paramError("[pageNumber] is inValid");
        }
        try {
            pageSize = (int) params.get(5);
        } catch (Exception e) {
            return RpcResult.paramError("[sortType] is inValid");
        }

        if (!AddressTool.validAddress(chainId, address)) {
            return RpcResult.paramError("[address] is inValid");
        }

        if (pageNumber <= 0) {
            pageNumber = 1;
        }
        if (pageSize <= 0 || pageSize > 100) {
            pageSize = 10;
        }

        PageInfo<FreezeInfo> pageInfo;
        if (CacheManager.isChainExist(chainId)) {
            Result<PageInfo<FreezeInfo>> result = WalletRpcHandler.getFreezeList(chainId, assetChainId, assetId, address, pageNumber, pageSize);
            if (result.isFailed()) {
                return RpcResult.failed(result);
            }
            pageInfo = result.getData();
            return RpcResult.success(pageInfo);
        } else {
            pageInfo = new PageInfo<>(pageNumber, pageSize);
            return RpcResult.success(pageInfo);
        }
    }

    @RpcMethod("getAccountBalance")
    public RpcResult getAccountBalance(List<Object> params) {
        VerifyUtils.verifyParams(params, 4);
        int chainId, assetChainId, assetId;
        String address;
        try {
            chainId = (int) params.get(0);
        } catch (Exception e) {
            return RpcResult.paramError("[chainId] is inValid");
        }
        try {
            assetChainId = (int) params.get(1);
        } catch (Exception e) {
            return RpcResult.paramError("[assetChainId] is inValid");
        }
        try {
            assetId = (int) params.get(2);
        } catch (Exception e) {
            return RpcResult.paramError("[assetId] is inValid");
        }
        try {
            address = (String) params.get(3);
        } catch (Exception e) {
            return RpcResult.paramError("[address] is inValid");
        }
        if (!AddressTool.validAddress(chainId, address)) {
            return RpcResult.paramError("[address] is inValid");
        }

        ApiCache apiCache = CacheManager.getCache(chainId);
        if (apiCache == null) {
            return RpcResult.dataNotFound();
        }
        if (assetId <= 0) {
            AssetInfo defaultAsset = apiCache.getChainInfo().getDefaultAsset();
            assetId = defaultAsset.getAssetId();
        }
        BalanceInfo balanceInfo = WalletRpcHandler.getAccountBalance(chainId, address, assetChainId, assetId);
        if (assetChainId == ApiContext.defaultChainId && assetId == ApiContext.defaultAssetId) {
            AccountInfo accountInfo = accountService.getAccountInfo(chainId, address);
            if (accountInfo != null) {
                balanceInfo.setConsensusLock(accountInfo.getConsensusLock());
            }
        }

        return RpcResult.success(balanceInfo);

    }

    @RpcMethod("getAccountsBalance")
    public RpcResult getAccountsBalance(List<Object> params) {
        VerifyUtils.verifyParams(params, 4);
        int chainId, assetChainId, assetId;
        String address;

        try {
            chainId = (int) params.get(0);
        } catch (Exception e) {
            return RpcResult.paramError("[chainId] is inValid");
        }
        try {
            assetChainId = (int) params.get(1);
        } catch (Exception e) {
            return RpcResult.paramError("[assetChainId] is inValid");
        }
        try {
            assetId = (int) params.get(2);
        } catch (Exception e) {
            return RpcResult.paramError("[assetId] is inValid");
        }
        try {
            address = (String) params.get(3);
        } catch (Exception e) {
            return RpcResult.paramError("[address] is inValid");
        }
        ApiCache apiCache = CacheManager.getCache(chainId);
        if (apiCache == null) {
            return RpcResult.dataNotFound();
        }
        if (assetId <= 0) {
            AssetInfo defaultAsset = apiCache.getChainInfo().getDefaultAsset();
            assetId = defaultAsset.getAssetId();
        }

        String[] addressList = address.split(",");
        Map<String, BalanceInfo> balanceInfoList = new HashMap<>();
        for (int i = 0; i < addressList.length; i++) {
            address = addressList[i];
            BalanceInfo balanceInfo = WalletRpcHandler.getAccountBalance(chainId, address, assetChainId, assetId);
            AccountInfo accountInfo = accountService.getAccountInfo(chainId, address);
            if (accountInfo != null) {
                balanceInfo.setConsensusLock(accountInfo.getConsensusLock());
            }
            balanceInfoList.put(address, balanceInfo);
        }
        return RpcResult.success(balanceInfoList);
    }


    @RpcMethod("isAliasUsable")
    public RpcResult isAliasUsable(List<Object> params) {
        VerifyUtils.verifyParams(params, 2);
        int chainId;
        String alias;
        try {
            chainId = (int) params.get(0);
        } catch (Exception e) {
            return RpcResult.paramError("[chainId] is inValid");
        }
        try {
            alias = (String) params.get(1);
        } catch (Exception e) {
            return RpcResult.paramError("[alias] is inValid");
        }
        if (StringUtils.isBlank(alias)) {
            return RpcResult.paramError("[alias] is inValid");
        }

        ApiCache apiCache = CacheManager.getCache(chainId);
        if (apiCache == null) {
            return RpcResult.dataNotFound();
        }

        Result result = WalletRpcHandler.isAliasUsable(chainId, alias);
        return RpcResult.success(result.getData());
    }

    @RpcMethod("getAccountLedgerList")
    public RpcResult getAccountLedgerList(List<Object> params) {
        VerifyUtils.verifyParams(params, 2);
        int chainId;
        String address;
        try {
            chainId = (int) params.get(0);
        } catch (Exception e) {
            return RpcResult.paramError("[chainId] is inValid");
        }
        try {
            address = (String) params.get(1);
        } catch (Exception e) {
            return RpcResult.paramError("[address] is inValid");
        }
        if (!AddressTool.validAddress(chainId, address)) {
            return RpcResult.paramError("[address] is inValid");
        }

        ApiCache apiCache = CacheManager.getCache(chainId);
        if (apiCache == null) {
            return RpcResult.dataNotFound();
        }
        List<AccountLedgerInfo> list = accountLedgerService.getAccountLedgerInfoList(chainId, address);
        for (AccountLedgerInfo ledgerInfo : list) {
            BalanceInfo balanceInfo = WalletRpcHandler.getAccountBalance(chainId, address, ledgerInfo.getChainId(), ledgerInfo.getAssetId());
            ledgerInfo.setBalance(balanceInfo.getBalance());
            ledgerInfo.setTimeLock(balanceInfo.getTimeLock());
            ledgerInfo.setConsensusLock(balanceInfo.getConsensusLock());
            AssetInfo assetInfo = CacheManager.getAssetInfoMap().get(ledgerInfo.getAssetKey());
            if (assetInfo != null) {
                ledgerInfo.setSymbol(assetInfo.getSymbol());
                ledgerInfo.setDecimals(assetInfo.getDecimals());
            }
        }
        return RpcResult.success(list);
    }


    @RpcMethod("getAccountCrossLedgerList")
    public RpcResult getAccountCrossLedgerList(List<Object> params) {
        VerifyUtils.verifyParams(params, 2);
        int chainId;
        String address;
        try {
            chainId = (int) params.get(0);
        } catch (Exception e) {
            return RpcResult.paramError("[chainId] is inValid");
        }
        try {
            address = (String) params.get(1);
        } catch (Exception e) {
            return RpcResult.paramError("[address] is inValid");
        }
        if (!AddressTool.validAddress(chainId, address)) {
            return RpcResult.paramError("[address] is inValid");
        }

        ApiCache apiCache = CacheManager.getCache(chainId);
        if (apiCache == null) {
            return RpcResult.dataNotFound();
        }
        List<AccountLedgerInfo> list = accountLedgerService.getAccountCrossLedgerInfoList(chainId, address);
        for (AccountLedgerInfo ledgerInfo : list) {
            BalanceInfo balanceInfo = WalletRpcHandler.getAccountBalance(chainId, address, ledgerInfo.getChainId(), ledgerInfo.getAssetId());
            ledgerInfo.setBalance(balanceInfo.getBalance());
            ledgerInfo.setTimeLock(balanceInfo.getTimeLock());
            ledgerInfo.setConsensusLock(balanceInfo.getConsensusLock());
            AssetInfo assetInfo = CacheManager.getAssetInfoMap().get(ledgerInfo.getAssetKey());
            if (assetInfo != null) {
                ledgerInfo.setSymbol(assetInfo.getSymbol());
                ledgerInfo.setDecimals(assetInfo.getDecimals());
            }
        }
        return RpcResult.success(list);

    }

    @RpcMethod("getAllAddressPrefix")
    public RpcResult getAllAddressPrefix(List<Object> params) {
        Result<List> result = WalletRpcHandler.getAllAddressPrefix();
        return RpcResult.success(result.getData());
    }

    @RpcMethod("getNRC20Snapshot")
    public RpcResult getNRC20Snapshot(List<Object> params) {
        VerifyUtils.verifyParams(params, 2);
        int chainId;
        String address;
        try {
            chainId = (int) params.get(0);
        } catch (Exception e) {
            return RpcResult.paramError("[chainId] is inValid");
        }
        try {
            address = (String) params.get(1);
        } catch (Exception e) {
            return RpcResult.paramError("[address] is inValid");
        }
        if (!AddressTool.validAddress(chainId, address)) {
            return RpcResult.paramError("[address] is inValid");
        }
        PageInfo<AccountTokenInfo> pageInfo = tokenService.getContractTokens(chainId, address, 1, Integer.MAX_VALUE);
        return RpcResult.success(pageInfo.getList().stream().map(d -> Map.of("address", d.getAddress(), "balance", d.getBalance())).collect(Collectors.toList()));
    }


}
