package com.zcode.cloudfrag;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * uiautomator dump 的 XML 解析（带父子关系）。
 * WebView 的 H5 节点是它的虚拟节点，层级保留着，所以能靠祖先容器把「按钮 ↔ 卡片标题」配起来。
 */
final class Ui {

    static final class Node {
        String text = "";
        String cls = "";
        String id = "";
        String pkg = "";
        boolean clickable;
        boolean enabled = true;
        int x1, y1, x2, y2;
        Node parent;
        final List<Node> kids = new ArrayList<>();

        int cx() {
            return (x1 + x2) / 2;
        }

        int cy() {
            return (y1 + y2) / 2;
        }

        int w() {
            return x2 - x1;
        }

        int h() {
            return y2 - y1;
        }

        /** bounds 有效（不在屏幕外的节点是 [0,0][0,0]）。 */
        boolean onScreen() {
            return x2 > x1 && y2 > y1;
        }

        boolean isButton() {
            return "android.widget.Button".equals(cls) && clickable;
        }

        boolean isText() {
            return cls != null && cls.startsWith("android.widget.TextView");
        }

        boolean has(String s) {
            return text != null && text.contains(s);
        }

        Node top() {
            Node n = this;
            while (n.parent != null) {
                n = n.parent;
            }
            return n;
        }

        void dump(StringBuilder sb, int depth) {
            for (int i = 0; i < depth; i++) {
                sb.append(' ');
            }
            sb.append('[').append(cls.replace("android.widget.", "")).append("] '")
                    .append(text).append("' ").append(x1).append(',').append(y1).append('-')
                    .append(x2).append(',').append(y2);
            if (clickable) {
                sb.append(" (可点)");
            }
            sb.append('\n');
            for (Node k : kids) {
                k.dump(sb, depth + 1);
            }
        }
    }

    private Ui() {
    }

    static List<Node> parse(String xml) {
        List<Node> all = new ArrayList<>();
        if (xml == null || xml.length() == 0) {
            return all;
        }
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(new StringReader(xml));
            Node stack = null;
            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    if ("node".equals(p.getName())) {
                        Node n = new Node();
                        n.text = attr(p, "text");
                        n.cls = attr(p, "class");
                        n.id = attr(p, "resource-id");
                        n.pkg = attr(p, "package");
                        n.clickable = "true".equals(attr(p, "clickable"));
                        n.enabled = "true".equals(attr(p, "enabled"));
                        parseBounds(attr(p, "bounds"), n);
                        n.parent = stack;
                        if (stack != null) {
                            stack.kids.add(n);
                        }
                        all.add(n);
                        stack = n;
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    if ("node".equals(p.getName()) && stack != null) {
                        stack = stack.parent;
                    }
                }
                ev = p.next();
            }
        } catch (Exception ignored) {
        }
        return all;
    }

    private static String attr(XmlPullParser p, String name) {
        String v = p.getAttributeValue(null, name);
        return v == null ? "" : v;
    }

    private static void parseBounds(String b, Node n) {
        if (b == null) {
            return;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").matcher(b);
        if (m.find()) {
            n.x1 = Integer.parseInt(m.group(1));
            n.y1 = Integer.parseInt(m.group(2));
            n.x2 = Integer.parseInt(m.group(3));
            n.y2 = Integer.parseInt(m.group(4));
        }
    }

    static List<Node> filter(List<Node> all, String clsEquals) {
        List<Node> out = new ArrayList<>();
        for (Node n : all) {
            if (clsEquals.equals(n.cls)) {
                out.add(n);
            }
        }
        return out;
    }

    static Node firstWithText(List<Node> all, String text) {
        for (Node n : all) {
            if (text.equals(n.text)) {
                return n;
            }
        }
        return null;
    }

    static Node firstContains(List<Node> all, String text) {
        for (Node n : all) {
            if (n.has(text)) {
                return n;
            }
        }
        return null;
    }
}
