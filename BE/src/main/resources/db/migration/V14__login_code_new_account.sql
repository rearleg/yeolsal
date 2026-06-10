alter table login_codes
    add column new_account boolean not null default false;
