package io.github.mateusvpassos.prelo.expr;

import java.math.BigDecimal;
import java.util.List;

/** Nó da árvore de uma expressão. */
public sealed interface Expr {
    Object eval(Scope scope);

    record Literal(Object value) implements Expr {
        public Object eval(Scope scope) {
            return value;
        }
    }

    record Ident(String name) implements Expr {
        public Object eval(Scope scope) {
            return scope.lookup(name);
        }
    }

    record Prop(Expr target, String name) implements Expr {
        public Object eval(Scope scope) {
            return Values.property(target.eval(scope), name);
        }
    }

    record Index(Expr target, Expr key) implements Expr {
        public Object eval(Scope scope) {
            return Values.index(target.eval(scope), key.eval(scope));
        }
    }

    record Call(String name, List<Expr> args) implements Expr {
        public Object eval(Scope scope) {
            return Functions.call(name, args, scope);
        }
    }

    record Unary(char op, Expr operand) implements Expr {
        public Object eval(Scope scope) {
            Object v = operand.eval(scope);
            if (op == '!') return !Values.truthy(v);
            BigDecimal n = Values.toNumber(v);
            return n == null ? null : n.negate();
        }
    }

    record Binary(String op, Expr left, Expr right) implements Expr {
        public Object eval(Scope scope) {
            switch (op) {
                case "&&": return Values.truthy(left.eval(scope)) && Values.truthy(right.eval(scope));
                case "||": return Values.truthy(left.eval(scope)) || Values.truthy(right.eval(scope));
                case "??": {
                    Object l = left.eval(scope);
                    return l != null ? l : right.eval(scope);
                }
                default: break;
            }
            Object a = left.eval(scope), b = right.eval(scope);
            return switch (op) {
                case "+" -> Values.add(a, b);
                case "-", "*", "/", "%" -> Values.arith(op.charAt(0), a, b);
                case "==" -> Values.equal(a, b);
                case "!=" -> !Values.equal(a, b);
                case "<" -> a != null && b != null && Values.compare(a, b) < 0;
                case "<=" -> a != null && b != null && Values.compare(a, b) <= 0;
                case ">" -> a != null && b != null && Values.compare(a, b) > 0;
                case ">=" -> a != null && b != null && Values.compare(a, b) >= 0;
                default -> throw new ExprException("operador desconhecido " + op);
            };
        }
    }

    record Conditional(Expr cond, Expr then, Expr otherwise) implements Expr {
        public Object eval(Scope scope) {
            return Values.truthy(cond.eval(scope)) ? then.eval(scope) : otherwise.eval(scope);
        }
    }
}
