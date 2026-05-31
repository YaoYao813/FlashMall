package com.example.flashmall.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.flashmall.user.entity.Address;
import com.example.flashmall.user.mapper.AddressMapper;
import com.example.flashmall.user.service.AddressService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
public class AddressServiceImpl implements AddressService {

    @Autowired
    private AddressMapper addressMapper;

    @Override
    public List<Address> listByUserId(Long userId) {
        return addressMapper.selectList(
                new LambdaQueryWrapper<Address>()
                        .eq(Address::getUserId, userId)
                        .orderByDesc(Address::getIsDefault)  // 默认地址排最前
                        .orderByDesc(Address::getCreateTime) // 最新的排前面
        );
    }

    @Override
    @Transactional
    public void add(Address address) {
        // 1. 如果设置为默认地址，先把其他地址的默认标记去掉
        if (address.getIsDefault() == 1) {
            clearDefaultAddress(address.getUserId());
        }

        // 2. 如果没有地址，自动设为默认
        long count = addressMapper.selectCount(
                new LambdaQueryWrapper<Address>()
                        .eq(Address::getUserId, address.getUserId())
        );
        if (count == 0) {
            address.setIsDefault(1);  // 第一个地址自动设为默认
        }

        // 3. 保存
        addressMapper.insert(address);
        log.info("新增地址：userId={}, addressId={}", address.getUserId(), address.getId());
    }

    @Override
    @Transactional
    public void update(Address address) {
        // 1. 先查询旧地址，确认属于当前用户
        Address oldAddress = addressMapper.selectById(address.getId());
        if (oldAddress == null) {
            throw new RuntimeException("地址不存在");
        }
        // 2. 如果设置为默认地址，先把其他地址的默认标记去掉
        if (address.getIsDefault() == 1) {
            clearDefaultAddress(oldAddress.getUserId());
        }
        // 3. 更新
        addressMapper.updateById(address);
        log.info("更新地址：addressId={}", address.getId());
    }

    @Override
    @Transactional
    public void delete(Long id) {
        // 1. 先查询地址，确认属于当前用户
        Address address = addressMapper.selectById(id);
        if (address == null) {
            throw new RuntimeException("地址不存在");
        }
        // 2. 删除
        addressMapper.deleteById(id);
        log.info("删除地址：addressId={}", id);
    }

    @Override
    public Address getDefaultAddress(Long userId) {
        return addressMapper.selectOne(
                new LambdaQueryWrapper<Address>()
                        .eq(Address::getUserId, userId)
                        .eq(Address::getIsDefault, 1)
        );
    }

    /**
     * 清除用户所有地址的默认标记
     */
    private void clearDefaultAddress(Long userId) {
        List<Address> defaultAddresses = addressMapper.selectList(
                new LambdaQueryWrapper<Address>()
                        .eq(Address::getUserId, userId)
                        .eq(Address::getIsDefault, 1)
        );
        for (Address addr : defaultAddresses) {
            addr.setIsDefault(0);
            addressMapper.updateById(addr);
        }
    }
}
