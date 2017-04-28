package com.vvsvip.common.dao;

import com.vvsvip.common.model.TransactionMessage;

public interface TransactionMessageMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(TransactionMessage record);

    int insertSelective(TransactionMessage record);

    TransactionMessage selectByPrimaryKey(Integer id);

    TransactionMessage selectByUUID(String uuid);

    int updateByPrimaryKeySelective(TransactionMessage record);

    int updateByPrimaryKey(TransactionMessage record);
}