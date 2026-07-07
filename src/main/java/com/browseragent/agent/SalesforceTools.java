package com.browseragent.agent;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * High-level, self-contained Salesforce automation tools.
 * Each method is a @Tool the LLM can call to perform a complete Salesforce action.
 *
 * These tools share the same browser session as BrowserTools — call
 * salesforceLogin() first so the LLM has an authenticated session to work with.
 */
@Slf4j
@Component
public class SalesforceTools {

    private static final long DEFAULT_TIMEOUT = 15_000;

    private final SimpMessagingTemplate ws;
    private final ReportService reportService;
    private final BrowserTools browserTools;

    public SalesforceTools(SimpMessagingTemplate ws, ReportService reportService, BrowserTools browserTools) {
        this.ws = ws;
        this.reportService = reportService;
        this.browserTools = browserTools;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private Page page() {
        return browserTools.getPage();
    }

    private void emit(String message) {
        log.info("[SalesforceTools] {}", message);
        ws.convertAndSend("/topic/logs", message);
    }

    // ─── Tools ────────────────────────────────────────────────────────────────

    @Tool("""
            Log into Salesforce using the given credentials.
            Navigates to the instance URL, fills username and password, then clicks Log In.
            Waits for the Lightning home page to load before returning.
            Example URL: https://login.salesforce.com or https://your-domain.my.salesforce.com
            """)
    public String salesforceLogin(String instanceUrl, String username, String password) {
        emit("🔑 Logging into Salesforce: " + instanceUrl);
        reportService.recordAction("Salesforce login to " + instanceUrl);

        try {
            page().navigate(instanceUrl);
            page().waitForLoadState(LoadState.DOMCONTENTLOADED);

            // Wait for the username field and fill credentials
            page().waitForSelector("#username", new Page.WaitForSelectorOptions().setTimeout(DEFAULT_TIMEOUT));
            page().fill("#username", username);
            page().fill("#password", password);
            page().click("#Login");

            // Wait for the main Lightning shell to load (the app nav selector or similar)
            page().waitForLoadState(LoadState.NETWORKIDLE);
            page().waitForSelector("one-app-nav-bar, .appName, .slds-global-header",
                    new Page.WaitForSelectorOptions().setTimeout(30_000));

            emit("✅ Logged in as " + username);
            reportService.recordSuccess("Logged into Salesforce as " + username);
            return "Successfully logged into " + instanceUrl + " as " + username
                    + ". Current page: " + page().title();

        } catch (Exception e) {
            String err = "Salesforce login failed: " + e.getMessage();
            emit("❌ " + err);
            reportService.recordError(err);
            return err;
        }
    }

    @Tool("""
            Close all open Lightning tabs, returning to the Home tab.
            In Salesforce Lightning, opens the Home page so tabs are cleared.
            """)
    public String closeAllTabs() {
        emit("🗂️ Closing all Salesforce tabs");
        reportService.recordAction("Close all Salesforce tabs");

        try {
            page().evaluate("""
                    () => {
                        const closeButtons = document.querySelectorAll(
                            '[data-tab-close], .closeIcon, .tabCloseButton, button[title*="Close"]'
                        );
                        closeButtons.forEach(btn => btn.click());
                    }
                    """);
            page().waitForTimeout(1000);

            page().navigate("/lightning/page/home");
            page().waitForLoadState(LoadState.DOMCONTENTLOADED);

            reportService.recordSuccess("Closed all tabs and returned to Home");
            return "All Salesforce tabs closed. Returned to Home page.";
        } catch (Exception e) {
            String err = "Failed to close tabs: " + e.getMessage();
            emit("❌ " + err);
            reportService.recordError(err);
            return err;
        }
    }

    @Tool("""
            Open the Salesforce App Launcher (the 9-dots grid icon).
            Use this before calling searchAndOpenApp() if you need to see available apps first.
            """)
    public String openAppLauncher() {
        emit("🔲 Opening App Launcher");
        reportService.recordAction("Open Salesforce App Launcher");

        try {
            String clicked = page().evaluate("""
                    () => {
                        const btn = document.querySelector(
                            'button[data-tooltip="App Launcher"], ' +
                            'button[data-tooltip="AppExchange Checker"], ' +
                            '[data-aura-class="appLauncher"], ' +
                            '.appLauncher button, ' +
                            'button[aria-label*="App"]'
                        );
                        if (btn) { btn.click(); return 'clicked'; }
                        return 'not-found';
                    }
                    """).toString();

            if ("not-found".equals(clicked)) {
                // Fallback: look for the grid SVG icon
                page().evaluate("""
                        () => {
                            const svg = document.querySelector('svg[data-icon-content*="app"]');
                            if (svg && svg.closest('button')) svg.closest('button').click();
                        }
                        """);
            }

            page().waitForTimeout(1500);
            String ok = "App Launcher opened";
            emit("✅ " + ok);
            reportService.recordSuccess(ok);
            return "App Launcher is now open. You can search for an app.";

        } catch (Exception e) {
            String err = "Failed to open App Launcher: " + e.getMessage();
            emit("❌ " + err);
            reportService.recordError(err);
            return err;
        }
    }

    @Tool("""
            Search for a Salesforce app by name in the App Launcher and open it.
            Call openAppLauncher() first if the launcher is not already open.
            Examples: 'Sales', 'Service', 'Marketing', 'Salesforce CMS'
            """)
    public String searchAndOpenApp(String appName) {
        emit("🔍 Searching and opening app: " + appName);
        reportService.recordAction("Search for app: " + appName);

        try {
            // Try to open launcher first if not already visible
            boolean launcherVisible = Boolean.parseBoolean(
                    page().evaluate("() => !!document.querySelector('.appLauncher, .modal-container, [role=\"dialog\"]')").toString());
            if (!launcherVisible) {
                openAppLauncher();
                page().waitForTimeout(800);
            }

            // Type in the search input
            page().evaluate("(name) => { const inp = document.querySelector('input[placeholder*=\"Search\"], input[type=\"search\"], .appLauncherSearchInput'); if (inp) { inp.value = ''; inp.focus(); } }", appName);
            page().fill("input[placeholder*=\"Search\"], input[type=\"search\"]", appName);
            page().waitForTimeout(1200);

            // Click the matching app tile
            String result = page().evaluate("""
                    (name) => {
                        const tiles = document.querySelectorAll(
                            '[data-label], .appTile, .al-item, a[data-label], a[role="option"]'
                        );
                        for (const t of tiles) {
                            const label = (t.getAttribute('data-label') || t.textContent || '').toLowerCase();
                            if (label.includes(name.toLowerCase())) {
                                t.click();
                                return label;
                            }
                        }
                        return null;
                    }
                    """, appName).toString();

            if (result == null || result.isBlank()) {
                // Fallback: press Enter
                page().keyboard().press("Enter");
            }

            page().waitForLoadState(LoadState.DOMCONTENTLOADED);
            page().waitForTimeout(2000);

            String ok = "Opened app: " + appName;
            emit("✅ " + ok);
            reportService.recordSuccess(ok);
            return "App \"" + appName + "\" opened. Current URL: " + page().url();

        } catch (Exception e) {
            String err = "Failed to open app '" + appName + "': " + e.getMessage();
            emit("❌ " + err);
            reportService.recordError(err);
            return err;
        }
    }

    @Tool("""
            Navigate to Salesforce Setup.
            Use this to configure objects, fields, users, security, etc.
            """)
    public String navigateToSetup() {
        emit("⚙️ Navigating to Setup");
        reportService.recordAction("Navigate to Salesforce Setup");

        try {
            page().navigate("/lightning/setup/SetupOneHome/home");
            page().waitForLoadState(LoadState.DOMCONTENTLOADED);
            reportService.recordSuccess("Navigated to Setup");
            return "Salesforce Setup opened. URL: " + page().url();
        } catch (Exception e) {
            String err = "Failed to navigate to Setup: " + e.getMessage();
            emit("❌ " + err);
            reportService.recordError(err);
            return err;
        }
    }

    @Tool("""
            Use the Quick Find search in Salesforce Setup.
            Call navigateToSetup() first if not already in Setup.
            Examples: 'Object Manager', 'Users', 'Profiles', 'Permission Sets', 'Sharing Settings'
            """)
    public String searchSetup(String searchTerm) {
        emit("🔎 Searching Setup for: " + searchTerm);
        reportService.recordAction("Search Setup: " + searchTerm);

        try {
            boolean onSetup = page().url().contains("/lightning/setup/");
            if (!onSetup) {
                navigateToSetup();
            }

            page().waitForSelector("#quickfindInput, input.setupSearch, input[placeholder*=\"Quick Find\"]",
                    new Page.WaitForSelectorOptions().setTimeout(DEFAULT_TIMEOUT));
            page().fill("#quickfindInput, input.setupSearch, input[placeholder*=\"Quick Find\"]", searchTerm);
            page().waitForTimeout(1000);
            page().keyboard().press("Enter");
            page().waitForLoadState(LoadState.DOMCONTENTLOADED);

            reportService.recordSuccess("Searched Setup for: " + searchTerm);
            return "Searched Setup for \"" + searchTerm + "\". Results should be visible on the page.";
        } catch (Exception e) {
            String err = "Setup search failed: " + e.getMessage();
            emit("❌ " + err);
            reportService.recordError(err);
            return err;
        }
    }

    @Tool("""
            Open a Salesforce object list view.
            Provide the API name of the object (e.g. Account, Contact, Opportunity, Case).
            Examples: 'Account' → /lightning/o/Account/list, 'Contact' → /lightning/o/Contact/list
            """)
    public String openObjectListView(String objectApiName) {
        emit("📋 Opening " + objectApiName + " list view");
        reportService.recordAction("Open object list view: " + objectApiName);

        try {
            String url = "/lightning/o/" + objectApiName + "/list";
            page().navigate(url);
            page().waitForLoadState(LoadState.DOMCONTENTLOADED);

            reportService.recordSuccess("Opened " + objectApiName + " list view");
            return "Opened " + objectApiName + " list view. URL: " + page().url();
        } catch (Exception e) {
            String err = "Failed to open " + objectApiName + " list view: " + e.getMessage();
            emit("❌ " + err);
            reportService.recordError(err);
            return err;
        }
    }

    @Tool("""
            Open the new record creation form for a given Salesforce object.
            Provide the API name (e.g. Account, Contact, Opportunity, Case, Lead).
            Examples: 'Account' → /lightning/o/Account/new, 'Contact' → /lightning/o/Contact/new
            """)
    public String createNewRecord(String objectApiName) {
        emit("➕ Creating new " + objectApiName + " record");
        reportService.recordAction("Create new " + objectApiName + " record");

        try {
            String url = "/lightning/o/" + objectApiName + "/new";
            page().navigate(url);
            page().waitForLoadState(LoadState.DOMCONTENTLOADED);

            reportService.recordSuccess("Opened new " + objectApiName + " record form");
            return "New " + objectApiName + " record form opened. URL: " + page().url();
        } catch (Exception e) {
            String err = "Failed to create new " + objectApiName + ": " + e.getMessage();
            emit("❌ " + err);
            reportService.recordError(err);
            return err;
        }
    }

    @Tool("""
            Open a Salesforce record by its 15 or 18 character ID.
            Provide the record ID (e.g. 0015g00000ABCD1234).
            Works for any standard or custom object record.
            """)
    public String openRecord(String recordId) {
        emit("📂 Opening record: " + recordId);
        reportService.recordAction("Open record: " + recordId);

        try {
            String url = "/lightning/r/" + recordId + "/view";
            page().navigate(url);
            page().waitForLoadState(LoadState.DOMCONTENTLOADED);

            reportService.recordSuccess("Opened record " + recordId);
            return "Record " + recordId + " opened. URL: " + page().url();
        } catch (Exception e) {
            String err = "Failed to open record " + recordId + ": " + e.getMessage();
            emit("❌ " + err);
            reportService.recordError(err);
            return err;
        }
    }

    @Tool("""
            Use the Salesforce global search box at the top of the page.
            Types the query and presses Enter, then waits for search results.
            Use this to search for records, reports, dashboards, etc.
            """)
    public String globalSearch(String query) {
        emit("🔍 Global Salesforce search: " + query);
        reportService.recordAction("Global search: " + query);

        try {
            page().evaluate("() => { const s = document.querySelector('input[data-global-search-input], input.globalSearch, input[placeholder*=\"Search\"], input[type=\"search\"]'); if (s) s.focus(); }");
            page().waitForTimeout(500);

            page().fill("input[data-global-search-input], input.globalSearch, input[placeholder*=\"Search\"], input[type=\"search\"]", query);
            page().keyboard().press("Enter");
            page().waitForLoadState(LoadState.DOMCONTENTLOADED);
            page().waitForTimeout(2000);

            reportService.recordSuccess("Global search for: " + query);
            return "Searched for \"" + query + "\". Results page URL: " + page().url();
        } catch (Exception e) {
            String err = "Search failed: " + e.getMessage();
            emit("❌ " + err);
            reportService.recordError(err);
            return err;
        }
    }

    @Tool("""
            List all currently open Lightning tabs and their labels.
            Returns a numbered list of tab names visible in the tab bar.
            """)
    public String listOpenTabs() {
        emit("📑 Listing open tabs");
        reportService.recordAction("List open Salesforce tabs");

        try {
            String tabs = page().evaluate("""
                    () => {
                        const items = document.querySelectorAll(
                            '.tabBarItems .tabItem, ' +
                            '[data-tab-name] button, ' +
                            '.oneHeaderTab, ' +
                            '.tabItemWrapper'
                        );
                        return Array.from(items).map((t, i) =>
                            (i + 1) + '. ' + (t.getAttribute('data-tab-label') || t.getAttribute('title') || t.textContent || '').trim()
                        ).join('\\n') || 'No open tabs found (or not on a Salesforce page).';
                    }
                    """).toString();

            reportService.recordObservation("Open tabs:\n" + tabs);
            return "Open tabs:\n" + tabs;
        } catch (Exception e) {
            return "Could not list tabs: " + e.getMessage();
        }
    }

    @Tool("""
            Switch to a Lightning tab by its label text.
            Provide the exact or partial tab label (e.g. 'Accounts', 'Opportunities').
            Use listOpenTabs() first to see available tabs.
            """)
    public String switchToTab(String tabLabel) {
        emit("🔄 Switching to tab: " + tabLabel);
        reportService.recordAction("Switch to tab: " + tabLabel);

        try {
            String result = page().evaluate("""
                    (label) => {
                        const items = document.querySelectorAll(
                            '.tabBarItems .tabItem, ' +
                            '[data-tab-name] button, ' +
                            '.oneHeaderTab, ' +
                            '.tabItemWrapper'
                        );
                        for (const t of items) {
                            const text = (t.getAttribute('data-tab-label') || t.getAttribute('title') || t.textContent || '').trim();
                            if (text.toLowerCase().includes(label.toLowerCase())) {
                                t.click();
                                return 'Clicked: ' + text;
                            }
                        }
                        return null;
                    }
                    """, tabLabel).toString();

            if (result == null || result.isBlank()) {
                return "Tab \"" + tabLabel + "\" not found. Use listOpenTabs() to see available tabs.";
            }

            page().waitForTimeout(2000);
            reportService.recordSuccess("Switched to tab: " + tabLabel);
            return result;
        } catch (Exception e) {
            String err = "Failed to switch tab: " + e.getMessage();
            emit("❌ " + err);
            reportService.recordError(err);
            return err;
        }
    }

    @Tool("""
            Close a specific Lightning tab by its label text.
            Provide the exact or partial tab label (e.g. 'Accounts').
            Use listOpenTabs() first to see available tabs.
            """)
    public String closeTab(String tabLabel) {
        emit("❌ Closing tab: " + tabLabel);
        reportService.recordAction("Close tab: " + tabLabel);

        try {
            String result = page().evaluate("""
                    (label) => {
                        const items = document.querySelectorAll(
                            '.tabBarItems .tabItem, ' +
                            '[data-tab-name] button, ' +
                            '.oneHeaderTab'
                        );
                        for (const t of items) {
                            const text = (t.getAttribute('data-tab-label') || t.getAttribute('title') || t.textContent || '').trim();
                            if (text.toLowerCase().includes(label.toLowerCase())) {
                                const closeBtn = t.querySelector('[data-tab-close], .closeIcon, .tabCloseButton, button[title*="Close"]');
                                if (closeBtn) { closeBtn.click(); return 'Closed: ' + text; }
                                t.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
                                return 'Double-clicked: ' + text;
                            }
                        }
                        return null;
                    }
                    """, tabLabel).toString();

            if (result == null || result.isBlank()) {
                return "Tab \"" + tabLabel + "\" not found. Use listOpenTabs() to see available tabs.";
            }

            page().waitForTimeout(1500);
            reportService.recordSuccess("Closed tab: " + tabLabel);
            return "Closed tab: " + result;
        } catch (Exception e) {
            String err = "Failed to close tab: " + e.getMessage();
            emit("❌ " + err);
            reportService.recordError(err);
            return err;
        }
    }

    @Tool("""
            Get information about the currently logged-in Salesforce user and org.
            Returns display name, org name, org ID, and user ID when available.
            """)
    public String getCurrentUserInfo() {
        emit("👤 Getting current user info");
        reportService.recordAction("Get current Salesforce user info");

        try {
            String info = page().evaluate("""
                    () => {
                        // Try the profile avatar / user menu area
                        const avatar = document.querySelector(
                            '.profileAvatar, .userProfileCard, [data-aura-class="userProfile"], user-avatar'
                        );
                        const headerText = document.querySelector(
                            '.userProfileCard .profile-card-name, ' +
                            '.oneUserProfileCard .name, ' +
                            '[data-aura-class="userProfile"] .uiMenu .zen-headerUserInfo, ' +
                            '.profile-card-wrapper .name'
                        );
                        let userName = headerText ? headerText.textContent.trim() : '';

                        // Org name from title or URL
                        const title = document.title;
                        let orgName = title.includes('|') ? title.split('|').pop().trim() : '';
                        const url = window.location.hostname;

                        return JSON.stringify({
                            userName: userName,
                            org: orgName || url,
                            title: title,
                            url: url
                        });
                    }
                    """).toString();

            reportService.recordObservation("User info: " + info);
            return "Salesforce User Info:\n" + info.replaceAll("[{}\",]", " ").replaceAll(":", ": ");
        } catch (Exception e) {
            return "Could not get user info: " + e.getMessage();
        }
    }

    @Tool("""
            Open the Developer Console in a new browser tab.
            After calling this, use getCurrentPage() from BrowserTools to see the new tab URL.
            """)
    public String openDeveloperConsole() {
        emit("🖥️ Opening Developer Console");
        reportService.recordAction("Open Developer Console");

        try {
            String url = page().evaluate("""
                    () => {
                        const base = window.location.origin;
                        return base + '/_ui/common/apex/debug/ApexCSIPage';
                    }
                    """).toString();
            page().navigate(url);
            page().waitForLoadState(LoadState.DOMCONTENTLOADED);

            reportService.recordSuccess("Developer Console opened");
            return "Developer Console opened. URL: " + page().url();
        } catch (Exception e) {
            String err = "Failed to open Developer Console: " + e.getMessage();
            emit("❌ " + err);
            reportService.recordError(err);
            return err;
        }
    }

    @Tool("""
            Open the Object Manager in Setup.
            Use this to find objects, fields, relationships, validation rules, etc.
            Equivalent to navigating to Setup → Object Manager.
            """)
    public String openObjectManager() {
        emit("📦 Opening Object Manager");
        reportService.recordAction("Open Object Manager");

        try {
            page().navigate("/lightning/setup/ObjectManager/home");
            page().waitForLoadState(LoadState.DOMCONTENTLOADED);
            reportService.recordSuccess("Object Manager opened");
            return "Object Manager opened. URL: " + page().url();
        } catch (Exception e) {
            String err = "Failed to open Object Manager: " + e.getMessage();
            emit("❌ " + err);
            reportService.recordError(err);
            return err;
        }
    }

    @Tool("""
            Switch between Salesforce Lightning Experience and Salesforce Classic.
            Pass 'lightning' or 'classic' as the mode.
            When switching to classic, the URL changes to the classic domain.
            """)
    public String switchExperience(String mode) {
        emit("🔄 Switching to " + mode + " experience");
        reportService.recordAction("Switch to " + mode + " experience");

        try {
            String target = mode.toLowerCase();
            if (target.startsWith("light") || target.startsWith("lex")) {
                // Already in Lightning — check URL
                if (page().url().contains("lightning")) {
                    return "Already in Lightning Experience.";
                }
                // Switch from classic: add /?ec=301 to URL
                String base = page().evaluate("() => window.location.origin").toString();
                page().navigate(base + "/?ec=301");
            } else if (target.startsWith("classic") || target.startsWith("clas")) {
                if (!page().url().contains("lightning")) {
                    return "Already in Salesforce Classic.";
                }
                // Switch from Lightning: navigate to /?ec=0 or classic URL
                String base = page().evaluate("() => window.location.origin").toString();
                page().navigate(base + "/?ec=0");
            } else {
                return "Unknown mode '" + mode + "'. Use 'lightning' or 'classic'.";
            }

            page().waitForLoadState(LoadState.DOMCONTENTLOADED);
            page().waitForTimeout(2000);

            reportService.recordSuccess("Switched to " + mode + " experience");
            return "Switched to " + mode + " experience. Current URL: " + page().url();
        } catch (Exception e) {
            String err = "Failed to switch experience: " + e.getMessage();
            emit("❌ " + err);
            reportService.recordError(err);
            return err;
        }
    }

    @Tool("""
            Navigate to the Salesforce Home tab.
            Equivalent to clicking the Home tab or navigating to /lightning/page/home.
            """)
    public String goToHome() {
        emit("🏠 Navigating to Home");
        reportService.recordAction("Go to Salesforce Home");

        try {
            page().navigate("/lightning/page/home");
            page().waitForLoadState(LoadState.DOMCONTENTLOADED);
            reportService.recordSuccess("Navigated to Home");
            return "Home page loaded. URL: " + page().url();
        } catch (Exception e) {
            String err = "Failed to go to Home: " + e.getMessage();
            emit("❌ " + err);
            reportService.recordError(err);
            return err;
        }
    }

    @Tool("""
            Open a Salesforce report by report ID.
            Provide the 15 or 18 character report ID.
            For browsing reports, use the Reports tab or globalSearch().
            """)
    public String openReport(String reportId) {
        emit("📊 Opening report: " + reportId);
        reportService.recordAction("Open report: " + reportId);

        try {
            page().navigate("/lightning/r/Report/" + reportId + "/view");
            page().waitForLoadState(LoadState.DOMCONTENTLOADED);
            reportService.recordSuccess("Opened report " + reportId);
            return "Report " + reportId + " opened. URL: " + page().url();
        } catch (Exception e) {
            String err = "Failed to open report: " + e.getMessage();
            emit("❌ " + err);
            reportService.recordError(err);
            return err;
        }
    }

    @Tool("""
            Open a Salesforce dashboard by dashboard ID.
            Provide the 15 or 18 character dashboard ID.
            For browsing dashboards, use the Dashboards tab or globalSearch().
            """)
    public String openDashboard(String dashboardId) {
        emit("📈 Opening dashboard: " + dashboardId);
        reportService.recordAction("Open dashboard: " + dashboardId);

        try {
            page().navigate("/lightning/r/Dashboard/" + dashboardId + "/view");
            page().waitForLoadState(LoadState.DOMCONTENTLOADED);
            reportService.recordSuccess("Opened dashboard " + dashboardId);
            return "Dashboard " + dashboardId + " opened. URL: " + page().url();
        } catch (Exception e) {
            String err = "Failed to open dashboard: " + e.getMessage();
            emit("❌ " + err);
            reportService.recordError(err);
            return err;
        }
    }

    @Tool("""
            Get the current page URL and verify you are on a Salesforce page.
            Useful for debugging or confirming navigation succeeded.
            """)
    public String getCurrentUrl() {
        try {
            String url = page().url();
            String title = page().title();
            return "URL: " + url + "\nTitle: " + title;
        } catch (Exception e) {
            return "Could not get URL: " + e.getMessage();
        }
    }
}
