package com.example.flashmall.user.controller;

import com.example.flashmall.common.util.Result;
import com.example.flashmall.user.entity.Address;
import com.example.flashmall.user.service.AddressService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/address")
public class AddressController {

    @Autowired
    private AddressService addressService;

    @GetMapping
    public Result list(@RequestHeader("X-User-Id") Long userId) {
        return Result.success(addressService.listByUserId(userId));
    }

    @PostMapping
    public Result add(@Valid @RequestBody Address address, @RequestHeader("X-User-Id") Long userId) {
        address.setUserId(userId);
        addressService.add(address);
        return Result.success(null, "添加成功");
    }

    @PutMapping("/{id}")
    public Result update(@PathVariable Long id, @Valid @RequestBody Address address) {
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
