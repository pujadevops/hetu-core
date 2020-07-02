REVOKE ROLES
============

Synopsis
--------

``` sql
REVOKE
[ ADMIN OPTION FOR ]
role [, ...]
FROM ( user | USER user | ROLE role) [, ...]
[ GRANTED BY ( user | USER user | ROLE role | CURRENT_USER | CURRENT_ROLE ) ]
```

Description
-----------

Revokes the specified role(s) from the specified principal(s) in the current catalog.

If the `ADMIN OPTION FOR` clause is specified, the `GRANT` permission is revoked instead of the role.

For the `REVOKE` statement for roles to succeed, the user executing it either should be the role admin or should possess the `GRANT` option for the given role.

The optional `GRANTED BY` clause causes the role(s) to be revoked with the specified principal as a revoker. If the `GRANTED BY` clause is not specified, the roles are revoked by the current user as a revoker.

Examples
--------

Revoke role `bar` from user `foo` :

    REVOKE bar FROM USER foo;

Revoke admin option for roles `bar` and `foo` from user `baz` and role `qux` :

    REVOKE ADMIN OPTION FOR bar, foo FROM USER baz, ROLE qux;

Limitations
-----------

Some connectors do not support role management. See connector documentation for more details.

See Also
--------

[create-role](./create-role.html), [drop-role](./drop-role.html), [set-role](./set-role.html), [grant-roles](./grant-roles.html)