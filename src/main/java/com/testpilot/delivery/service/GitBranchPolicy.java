package com.testpilot.delivery.service;

import com.testpilot.common.exception.InvalidRequestException;
import org.springframework.stereotype.Component;

@Component
public class GitBranchPolicy {

    public String requireSafeBaseBranch(String branch) {
        if (!isSafe(branch)) {
            throw new InvalidRequestException("Repository default branch is not a safe Git reference");
        }
        return branch;
    }

    public String requireDeliveryBranch(String branch, String baseBranch) {
        if (!isSafe(branch) || !branch.startsWith("testpilot/") || branch.equals(baseBranch)) {
            throw new InvalidRequestException("Delivery must target a dedicated testpilot branch");
        }
        return branch;
    }

    private boolean isSafe(String value) {
        return value != null
                && !value.isBlank()
                && value.length() <= 255
                && !value.startsWith("/")
                && !value.endsWith("/")
                && !value.endsWith(".")
                && !value.endsWith(".lock")
                && !value.contains("..")
                && !value.contains("@{")
                && value.chars().noneMatch(Character::isWhitespace)
                && value.chars().noneMatch(character -> "~^:?*[\\".indexOf(character) >= 0)
                && value.chars().allMatch(character -> character >= 32 && character != 127);
    }
}
