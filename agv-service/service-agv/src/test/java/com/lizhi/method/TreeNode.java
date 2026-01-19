package com.lizhi.method;

import org.antlr.runtime.tree.Tree;

public class TreeNode {
    public TreeNode left;
    public int value;
    public TreeNode right;

    public TreeNode(int value) {
        this.value = value;
    }

    public TreeNode(TreeNode left, int value, TreeNode right) {
        this.left = left;
        this.value = value;
        this.right = right;
    }

    @Override
    public String toString() {
        return String.valueOf(this.value);
    }
}
