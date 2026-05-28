package com.example.flashmall.user.service;

import com.example.flashmall.user.entity.Address;
import java.util.List;

public interface AddressService {
    List<Address> listByUserId(Long userId);
    void add(Address address);
    void update(Address address);
    void delete(Long id);
    Address getDefaultAddress(Long userId);

}
