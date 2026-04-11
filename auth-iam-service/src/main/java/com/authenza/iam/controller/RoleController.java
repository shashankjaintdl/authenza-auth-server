package com.authenza.iam.controller;

import com.authenza.common.constant.AuthenzaConstant;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RoleController.ENDPOINT)
public class RoleController {

    public static final String ENDPOINT = AuthenzaConstant.API_VERSION + AuthenzaConstant.TENANT_PATH;

    

}
