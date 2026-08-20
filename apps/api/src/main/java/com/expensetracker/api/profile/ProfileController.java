package com.expensetracker.api.profile;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.expensetracker.api.security.CurrentUser;

@RestController
@RequestMapping("/api")
public class ProfileController {

    private final ProfileService profiles;
    private final JdbcTemplate jdbc;

    public ProfileController(ProfileService profiles, JdbcTemplate jdbc) {
        this.profiles = profiles;
        this.jdbc = jdbc;
    }

    /** Returns the caller's profile, provisioning defaults on first call. */
    @GetMapping("/me")
    public ProfileView me() {
        return profiles.ensureProvisioned(CurrentUser.id(), CurrentUser.email());
    }

    /** The caller's category tree, used by the transaction editor. */
    @GetMapping("/categories")
    public List<Map<String, Object>> categories() {
        return jdbc.queryForList("""
                select id, parent_id, name, icon, is_system, sort_order
                from categories
                where user_id = ?
                order by sort_order, name
                """, CurrentUser.id());
    }
}
