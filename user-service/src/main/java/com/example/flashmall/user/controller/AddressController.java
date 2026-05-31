package com.example.flashmall.user.controller;
// 地址管理接口

import com.example.flashmall.common.util.Result;
import com.example.flashmall.user.entity.Address;
import com.example.flashmall.user.service.AddressService;
import com.example.flashmall.user.util.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/address")
public class AddressController {

    @Autowired
    private AddressService addressService;

    @GetMapping
    public Result list() {
        Long userId = UserContext.getUserId();
        return Result.success(addressService.listByUserId(userId));
    }

    @PostMapping
    public Result add(@RequestBody Address address) {
        address.setUserId(UserContext.getUserId());
        addressService.add(address);
        return Result.success(null, "添加成功");
    }

    @PutMapping("/{id}")
    public Result update(@PathVariable Long id, @RequestBody Address address) {
        address.setId(id);
        addressService.update(address);
        return Result.success(null, "更新成功");
    }

    @DeleteMapping("/{id}")
    public Result delete(@PathVariable Long id) {
        addressService.delete(id);
        return Result.success(null, "删除成功");
    }
}
