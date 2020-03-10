package com.m1.af.api.services.teams.impl;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.m1.af.api.exceptions.InvalidInputI18nException;
import com.m1.af.api.exceptions.OperationFailedI18nException;
import com.m1.af.api.exceptions.ResourceNotFoundI18nException;
import com.m1.af.api.model.team.DefaultTeamLeaderPermissions;
import com.m1.af.api.model.team.TeamGreeting;
import com.m1.af.api.model.team.TeamLeaderUserInfo;
import com.m1.af.api.model.team.TeamListParameters;
import com.m1.af.api.model.team.TeamMember;
import com.m1.af.api.reports.TeamListReport;
import com.m1.af.api.services.InstancePool;
import com.m1.af.api.services.ManagementContainer;
import com.m1.af.api.services.PermissionService;
import com.m1.af.api.services.users.UserListService;
import com.m1.af.api.test.BaseMockitoTest;
import com.m1.cmc.mgmt.AFPermission;
import com.m1.cmc.mgmt.BroadcastGroup;
import com.m1.cmc.mgmt.ControllerContext;
import com.m1.cmc.mgmt.Customer;
import com.m1.cmc.mgmt.DataSource;
import com.m1.cmc.mgmt.DynamicGroup;
import com.m1.cmc.mgmt.EscalationGroup;
import com.m1.cmc.mgmt.IBroadcastGroupManager;
import com.m1.cmc.mgmt.IDynamicGroupManager;
import com.m1.cmc.mgmt.IEscalationGroupManager;
import com.m1.cmc.mgmt.IEventLogger;
import com.m1.cmc.mgmt.IIncidentManager;
import com.m1.cmc.mgmt.ITeamManager;
import com.m1.cmc.mgmt.IUserManager;
import com.m1.cmc.mgmt.Incident;
import com.m1.cmc.mgmt.MemberType;
import com.m1.cmc.mgmt.Team;
import com.m1.cmc.mgmt.TeamCapabilities;
import com.m1.cmc.mgmt.TeamLeader;
import com.m1.cmc.mgmt.TeamManager;
import com.m1.cmc.mgmt.TeamMapCache;
import com.m1.cmc.mgmt.User;
import com.m1.cmc.mgmt.UserManager;
import com.m1.cmc.mgmt.data.UiSettings;
import com.m1.cmc.ui.UIPermission;
import com.m1.cmc.ui.UserContext;
import com.m1.util.mgmt.SearchConstraint;
import com.m1.util.mgmt.SearchOperator;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.reflect.Whitebox;
import org.springframework.context.MessageSource;

import java.io.PrintWriter;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.m1.af.api.services.teams.impl.TeamServiceImpl.FILE_EXTENSION_CSV;
import static com.m1.cmc.mgmt.IUserManager.ROLE_CUST_ADMIN;
import static com.m1.cmc.mgmt.TeamCapabilities.CAP_FROM_TEAMEMAIL_ADDR;
import static com.m1.cmc.mgmt.TeamCapabilities.CAP_FROM_TEAMVOICENOTIFICATIONSYSTEM;
import static org.assertj.core.api.Java6Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyList;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.powermock.api.mockito.PowerMockito.doReturn;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.mockStatic;
import static org.powermock.api.mockito.PowerMockito.when;
import static org.powermock.api.support.membermodification.MemberMatcher.methods;
import static org.powermock.api.support.membermodification.MemberModifier.suppress;

@RunWith(PowerMockRunner.class)
@PrepareForTest({UIPermission.class, AFPermission.class})
public class TeamServiceImplTest extends BaseMockitoTest {

    private static final String PARENT_TEAM_NAME = "ParentTeam";
    private static final String SUB_TEAM_NAME = "SubTeam";
    private static final long PARENT_TEAM_ID = 100;
    private static final long SUB_TEAM_ID = 200;

    private static final long CUSTOMER_ADMIN_ID = 10;
    private static final long CUSTOMER_USER_ID = 20;

    private static final long PERMISSION_PARENT = 2048;
    private static final long PERMISSION_SUB = 4096;

    private static final String EXISTING_TEAM_NAME = "ExistingTeam";
    private static final long EXISTING_TEAM_ID = 400L;
    private static final String SAMPLE_TEAM_NAME = "SampleTeam";
    private static final long SAMPLE_TEAM_ID = 300L;

    private static final Team TEAM = mockTeam(1L, "Team 1");
    private static final List<Team> SUB_TEAMS = Collections.unmodifiableList(Arrays.asList(
        mockTeam(2L, "Team 1.1"),
        mockTeam(3L, "Team 1.2")
    ));

    private static final String CAP_TEAM = "team";
    private static final String EVENT_CREATE = "created";
    private static final String EVENT_UPDATE = "updated";
    private static final String EVENT_DELETE = "deleted";

    private static Team mockTeam(Long id, String name) {
        Team team = mock(Team.class);
        when(team.getId()).thenReturn(id);
        when(team.getName()).thenReturn(name);
        return team;
    }
    @Captor
    private ArgumentCaptor<List<SearchConstraint>> constraintCaptor;

    private TeamServiceImpl object;
    private UserContext userContext;
    private TeamManager manager;
    private InstancePool instancePool;
    private UserManager userManager;
    private PermissionService permissionService;
    private MessageSource messageSource;
    private UserListService userListService;
    private IIncidentManager incidentManager;
    private IBroadcastGroupManager broadcastGroupManager;
    private IEscalationGroupManager escalationGroupManager;
    private IDynamicGroupManager dynamicGroupManager;
    private IEventLogger eventLogger;

    private User user;

    private Team parentTeamToList;
    private Team subTeamToList;
    private User customerAdminToListTeams;
    private User customerUserToListTeams;


    @Before
    public void setUp() {
        mockStatic(UIPermission.class);
        mockStatic(AFPermission.class);
        suppress(methods(UIPermission.class, "validate"));
        ManagementContainer managementContainer = mock(ManagementContainer.class);
        instancePool = mock(InstancePool.class);
        manager = mock(TeamManager.class);
        messageSource = mock(MessageSource.class);
        permissionService = mock(PermissionService.class);
        userListService = mock(UserListService.class);
        incidentManager = mock(IIncidentManager.class);
        broadcastGroupManager = mock(IBroadcastGroupManager.class);
        escalationGroupManager = mock(IEscalationGroupManager.class);
        dynamicGroupManager = mock(IDynamicGroupManager.class);
        eventLogger = mock(IEventLogger.class);

        when(managementContainer.getBroadcastGroupManager()).thenReturn(broadcastGroupManager);
        when(managementContainer.getDynamicGroupManager()).thenReturn(dynamicGroupManager);
        when(managementContainer.getEscalationGroupManager()).thenReturn(escalationGroupManager);

        when(manager.getTeam(1L)).thenReturn(TEAM);
        when(manager.getSubteams(TEAM, 0, -1)).thenReturn(SUB_TEAMS);
        when(manager.getGlobal(1L)).thenReturn(TEAM);
        Mockito.doReturn(eventLogger).when(managementContainer).getEventLogger();

        userManager = mock(UserManager.class);
        when(managementContainer.getUserManager()).thenReturn(userManager);

        Customer customer = mock(Customer.class);
        when(customer.getId()).thenReturn(1L);
        userContext = mock(UserContext.class);
        when(instancePool.getUserContext()).thenReturn(userContext);
        when(userContext.getCurrentCustomer()).thenReturn(customer);
        when(userContext.getCurrentTeam()).thenReturn(TEAM);
        when(userContext.toControllerContext()).thenReturn(mock(ControllerContext.class));
        user = mock(User.class);
        when(userContext.getActualUser()).thenReturn(user);
        when(userContext.getCurrentUser()).thenReturn(user);
        when(userContext.getGlobalTeam()).thenReturn(TEAM);
        when(instancePool.getCurrentCustomerId()).thenReturn(1L);
        long teamId = TEAM.getId();
        when(instancePool.getCurrentTeamId()).thenReturn(teamId);

        when(managementContainer.getIncidentManager()).thenReturn(incidentManager);
        when(managementContainer.getTeamManager()).thenReturn(manager);

        object = spy(new TeamServiceImpl(managementContainer, instancePool, permissionService, messageSource, userListService));
    }

    @Test
    public void getGlobal() {
        com.m1.af.api.model.team.Team team = object.getGlobalTeam();

        assertTeamEquals(team);
    }

    @Test(expected = ResourceNotFoundI18nException.class)
    public void getGlobal_noCustomer() {
        when(userContext.getCurrentCustomer()).thenReturn(null);
        object.getGlobalTeam();
    }

    @Test
    public void getByIdPopulated() {
        mockTeamMapCacheForParentTeam();

        com.m1.af.api.model.team.Team team = object.getById(1L, true);

        assertTeamEquals(team);
        verify(manager, atLeastOnce()).populateLeaders(anyList());
        verify(manager, atLeastOnce()).populateMembers(anyList());
    }

    private static void assertTeamEquals(final com.m1.af.api.model.team.Team team) {
        assertEquals(TEAM.getId(), (long) team.getId());
        assertEquals(TEAM.getName(), team.getName());
        assertEquals(TEAM.getSubteams().size(), team.getSubTeams().size());
    }

    @Test
    public void getByIdNotPopulated() {
        com.m1.af.api.model.team.Team team = object.getById(1L, false);

        assertTeamEquals(team);
        verify(manager, times(0)).populateLeaders(anyList());
        verify(manager, times(0)).populateMembers(anyList());
    }

    @Test
    public void givenPopulateTeamMembersIsFalseWhenGetByIdThenPopulateMembersNeverInvoked() {
        mockTeamMapCacheForParentTeam();

        com.m1.af.api.model.team.Team team = object.getById(1L, true, false);

        assertTeamEquals(team);
        verify(manager, never()).populateMembers(any());
    }

    @Test
    public void givenPopulateTeamMembersIsTrueWhenGetByIdThenPopulateMembersInvoked() {
        mockTeamMapCacheForParentTeam();

        com.m1.af.api.model.team.Team team = object.getById(1L, true, true);

        assertTeamEquals(team);
        verify(manager, times(1)).populateMembers(any());
    }

    private void mockTeamMapCacheForParentTeam() {
        TeamMapCache cache = mock(TeamMapCache.class);
        when(object.getTeamMapCache()).thenReturn(cache);
        when(cache.getParents(SUB_TEAM_ID)).thenReturn(Collections.singletonList(PARENT_TEAM_ID));
    }

    @Test(expected = ResourceNotFoundI18nException.class)
    public void getById_notFound() {
        object.getById(2L, true);
    }

    @Test
    public void getCurrentTeamShouldProxyToCmc() {
        // Arrange
        final TeamMapCache cache = mock(TeamMapCache.class);
        when(object.getTeamMapCache()).thenReturn(cache);
        when(cache.getParents(SUB_TEAM_ID)).thenReturn(Collections.emptyList());
        final com.m1.af.api.model.team.Team expectedTeam = new com.m1.af.api.model.team.Team(TEAM.getId(), TEAM.getName(),
            TEAM.getDescription(), false, false, 0L,
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        // Act
        final com.m1.af.api.model.team.Team result = object.getCurrentTeam();

        // Assert
        verify(instancePool).getCurrentTeamId();
        verify(manager, never()).populateLeaders(anyList());
        verify(manager, never()).populateMembers(anyList());
        verify(manager, never()).getSubteams(any(), anyInt(), anyInt());
        equalsToCollector(expectedTeam, result);
    }

    @Test
    public void shouldSetCurrentTeam() {
        final long customerID = 1000L;
        ArgumentCaptor<Team> captor = ArgumentCaptor.forClass(Team.class);
        Team mockTeam = mockTeam(SAMPLE_TEAM_ID, SAMPLE_TEAM_NAME);
        when(manager.getTeam(SAMPLE_TEAM_ID)).thenReturn(mockTeam);
        when(instancePool.getCurrentCustomerId()).thenReturn(customerID);
        when(mockTeam.getCustomerId()).thenReturn(customerID);

        object.setCurrentTeam(SAMPLE_TEAM_ID);

        verify(userContext).refreshCurrentTeam(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(SAMPLE_TEAM_ID);
    }

    @Test
    public void shouldSetCurrentTeamToGlobalWhenTeamIdIsNull() {
        ArgumentCaptor<Team> captor = ArgumentCaptor.forClass(Team.class);

        object.setCurrentTeam(null);

        verify(userContext).refreshCurrentTeam(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(TEAM.getId());
    }

    private void setupForTeamListing(){
        parentTeamToList = createLegacyTeam(PARENT_TEAM_ID, PARENT_TEAM_NAME);
        parentTeamToList.setLeaders(Collections.singletonList(
            createLegacyTeamLeader(CUSTOMER_ADMIN_ID, PARENT_TEAM_ID, PERMISSION_PARENT)));

        subTeamToList = createLegacyTeam(SUB_TEAM_ID, SUB_TEAM_NAME);
        subTeamToList.setLeaders(Collections.singletonList(
            createLegacyTeamLeader(CUSTOMER_USER_ID, SUB_TEAM_ID, PERMISSION_SUB)));

        customerAdminToListTeams = mock(User.class);
        when(customerAdminToListTeams.getCustomerId()).thenReturn(1L);
        when(customerAdminToListTeams.getRole()).thenReturn(ROLE_CUST_ADMIN);
        when(customerAdminToListTeams.getId()).thenReturn(CUSTOMER_ADMIN_ID);
        when(customerAdminToListTeams.getLeaderOfTeamsMap())
            .thenReturn(ImmutableMap.of(PARENT_TEAM_ID, parentTeamToList, SUB_TEAM_ID, subTeamToList));

        customerUserToListTeams = mock(User.class);
        when(customerUserToListTeams.getCustomerId()).thenReturn(1L);
        when(customerUserToListTeams.getRole()).thenReturn(IUserManager.ROLE_CUST_USER);
        when(customerUserToListTeams.getId()).thenReturn(CUSTOMER_USER_ID);
        when(customerUserToListTeams.getLeaderOfTeamsMap())
            .thenReturn(ImmutableMap.of(SUB_TEAM_ID, subTeamToList));

        mockTeamMapCacheForParentTeam();

        Whitebox.setInternalState(UIPermission.class, "VIEW_MYTEAMS_LIST", mock(UIPermission.class));
        Whitebox.setInternalState(UIPermission.class, "VIEW_USER", mock(UIPermission.class));

        when(manager.find(any(List.class), any(List.class), anyInt(), anyInt())).then( invocation -> {
            List<Team> result = Arrays.asList(parentTeamToList, subTeamToList);
            List<SearchConstraint>  constraints = ((List<SearchConstraint>) invocation.getArguments()[0]).stream()
                .filter(constraint -> constraint.getProperty().equals(ITeamManager.PROP_TEAM_ID))
                .filter(constraint -> constraint.getOperator().equals(SearchOperator.IN_LIST)).collect(Collectors.toList());

            for( SearchConstraint constraint : constraints){
                result = result.stream().filter(item -> ((Collection)constraint.getValue()).contains(item.getId()))
                    .collect(Collectors.toList());
            }

            return result;
        });

        when(manager.find(any(List.class))).then( invocation -> {
            List<Team> result = Arrays.asList(parentTeamToList, subTeamToList);
            List<SearchConstraint>  constraints = ((List<SearchConstraint>) invocation.getArguments()[0]).stream()
                .filter(constraint -> constraint.getProperty().equals(ITeamManager.PROP_TEAM_ID))
                .filter(constraint -> constraint.getOperator().equals(SearchOperator.IN_LIST)).collect(Collectors.toList());

            for( SearchConstraint constraint : constraints){
                result = result.stream().filter(item -> ((Collection)constraint.getValue()).contains(item.getId()))
                    .collect(Collectors.toList());
            }

            return result;
        });


        when(userManager.findUsers(any(List.class))).then( invocation -> {
            List<User> result = Arrays.asList(customerAdminToListTeams, customerUserToListTeams);
            List<SearchConstraint>  constraints = ((List<SearchConstraint>) invocation.getArguments()[0]).stream()
                .filter(constraint -> constraint.getProperty().equals(IUserManager.PROP_USER_ID))
                .filter(constraint -> constraint.getOperator().equals(SearchOperator.IN_LIST)).collect(Collectors.toList());

            for( SearchConstraint constraint : constraints){
                result = result.stream().filter(item -> ((Collection)constraint.getValue()).contains(item.getId()))
                    .collect(Collectors.toList());
            }

            return result;
        });

        when(AFPermission.isAdminEqual(customerAdminToListTeams)).thenReturn(true);
        when(AFPermission.isAdminEqual(customerUserToListTeams)).thenReturn(false);

    }

    @Test
    public void listTeamsAsCustomerAdminShouldReturnTeams(){
        setupForTeamListing();
        when(userContext.getCurrentUser()).thenReturn(customerAdminToListTeams);

        assertThat(object.listTeams(new TeamListParameters()).getContent()).extracting("id")
            .hasSameElementsAs(Arrays.asList(PARENT_TEAM_ID, SUB_TEAM_ID));
    }

    @Test
    public void listTeamsAsCustomerUserShouldReturnTeams(){
        setupForTeamListing();
        when(userContext.getCurrentUser()).thenReturn(customerUserToListTeams);
        TeamListParameters parameters = new TeamListParameters();
        parameters.setSubTeams(true);
        assertThat(object.listTeams(parameters).getContent()).extracting("id")
            .hasSameElementsAs(Arrays.asList(SUB_TEAM_ID));
    }

    @Test
    public void listTeamsAsCustomerUserShouldNotReturnSubTeamsWhenSetToFalse(){
        setupForTeamListing();
        when(userContext.getCurrentUser()).thenReturn(customerUserToListTeams);

        when(customerUserToListTeams.getLeaderOfTeamsMap())
            .thenReturn(ImmutableMap.of(SUB_TEAM_ID, subTeamToList, PARENT_TEAM_ID, parentTeamToList));

        when(customerUserToListTeams.getDirectLeaderOfTeamsMap())
            .thenReturn(ImmutableMap.of(SUB_TEAM_ID, subTeamToList));

        TeamListParameters parameters = new TeamListParameters();
        parameters.setSubTeams(false);
        assertThat(object.listTeams(parameters).getContent()).extracting("id")
            .hasSameElementsAs(Arrays.asList(SUB_TEAM_ID));
    }

    @Test
    public void listTeamsShouldContainLeaderInfo() {
        setupForTeamListing();
        when(userContext.getCurrentUser()).thenReturn(customerUserToListTeams);

        TeamListParameters parameters = new TeamListParameters();
        parameters.setPopulateLeaderInfo(true);
        parameters.setSubTeams(true);
        List<com.m1.af.api.model.team.Team> teams = object.listTeams(parameters).getContent();

        for (com.m1.af.api.model.team.Team team : teams) {
            if (team.getId() == PARENT_TEAM_ID) {
                assertThat(team.getLeaders().size()).isEqualTo(1);
                assertThat(team.getLeaders()).extracting("userId")
                    .hasSameElementsAs(Collections.singletonList(CUSTOMER_ADMIN_ID));
            } else if (team.getId() == SUB_TEAM_ID) {
                assertThat(team.getLeaders().size()).isEqualTo(2);
                assertThat(team.getLeaders()).extracting("userId")
                    .hasSameElementsAs(Arrays.asList(CUSTOMER_ADMIN_ID, CUSTOMER_USER_ID));
            }
        }
    }

    @Test
    public void listTeamsShouldNotUseCache() {
        setupForTeamListing();
        when(userContext.getCurrentUser()).thenReturn(customerUserToListTeams);

        TeamListParameters parameters = new TeamListParameters();
        parameters.setPopulateLeaderInfo(true);
        parameters.setSubTeams(true);
        object.listTeams(parameters).getContent();

        verify(object).buildConstraints(parameters, false);
    }

    @Test
    public void buildConstraintShouldUpdateCache() {
        setupForTeamListing();
        TeamMapCache teamMapCache = mock(TeamMapCache.class);
        when(userContext.getCurrentUser()).thenReturn(customerUserToListTeams);

        TeamListParameters parameters = new TeamListParameters();
        parameters.setPopulateLeaderInfo(true);
        parameters.setSubTeams(true);
        object.buildConstraints(parameters, false);

        doAnswer(i -> i.getArguments()[0])
            .when(teamMapCache)
            .clear(anyLong());
        verify(manager).populateLeaderOfTeamMap(customerUserToListTeams);
    }

    @Test
    public void buildConstraintShouldNotUpdateCache() {
        setupForTeamListing();
        when(userContext.getCurrentUser()).thenReturn(customerUserToListTeams);

        TeamListParameters parameters = new TeamListParameters();
        parameters.setPopulateLeaderInfo(true);
        parameters.setSubTeams(true);
        object.buildConstraints(parameters, true);

        verify(manager, never()).populateLeaderOfTeamMap(customerUserToListTeams);
    }

    @Test
    public void listTeamSummariesAsCustomerAdminShouldReturnTeamSummaries() {
        setupForTeamListing();
        when(userContext.getCurrentUser()).thenReturn(customerAdminToListTeams);

        assertThat(object.listTeamSummaries()).extracting("id")
            .hasSameElementsAs(Arrays.asList(PARENT_TEAM_ID, SUB_TEAM_ID));
    }

    @Test
    public void listAsCsvShouldReturnTeamListReport() {
        setupForTeamListing();

        final TeamListReport report = object.listCsv(new TeamListParameters());

        assertThat(report.getContent()).contains("ParentTeam,,Enabled");
        assertThat(report.getContent()).contains("SubTeam,,Enabled");
    }

    private void setupForCreateTeam() {
        when(manager.find(any(List.class))).then( invocation -> {
            List<SearchConstraint>  constraints = ((List<SearchConstraint>) invocation.getArguments()[0]).stream()
                .filter(constraint -> constraint.equals(
                    new SearchConstraint(ITeamManager.PROP_TEAM_NAME, SearchOperator.EQUALS, EXISTING_TEAM_NAME)))
                .collect(Collectors.toList());

            if (! constraints.isEmpty()) {
                return Collections.singletonList(createTeam(EXISTING_TEAM_ID, EXISTING_TEAM_NAME));
            } else {
                return Collections.emptyList();
            }
        });

        when(manager.populateSubteams(any(List.class))).then( invocation -> {
            ((List<Team>) invocation.getArguments()[0]).forEach(team -> team.setSubteams(new ArrayList<>()));
            return 1;
        });

        Team sampleTeam = createLegacyTeam(SAMPLE_TEAM_ID, SAMPLE_TEAM_NAME);

        when(manager.get(PARENT_TEAM_ID)).thenReturn(createLegacyTeam(PARENT_TEAM_ID, PARENT_TEAM_NAME));
        when(manager.create(any(DataSource.class), anyLong(), anyString(), anyString()))
            .thenReturn(sampleTeam);
        when(manager.update(any(List.class))).thenReturn(1);
    }

    @Test
    public void shouldNotCreateTeamWithDuplicateName(){
        com.m1.af.api.model.team.Team sampleTeam = createTeam(0L, EXISTING_TEAM_NAME);
        expectedException.expect(InvalidInputI18nException.class);

        setupForCreateTeam();

        object.createTeam(sampleTeam);
    }

    @Test
    public void shouldNotCreateTeamWhenParentCantBeFound(){
        com.m1.af.api.model.team.Team sampleTeam = createTeam(0L, SAMPLE_TEAM_NAME);
        sampleTeam.setParentId(PARENT_TEAM_ID);

        setupForCreateTeam();
        when(manager.get(PARENT_TEAM_ID)).thenReturn(null);

        expectedException.expect(OperationFailedI18nException.class);

        object.createTeam(sampleTeam);
    }

    @Test
    public void shouldFailWhenTeamCannotBeCreatedOnManager(){
        com.m1.af.api.model.team.Team sampleTeam = createTeam(0L, SAMPLE_TEAM_NAME);
        sampleTeam.setParentId(PARENT_TEAM_ID);
        setupForCreateTeam();
        when(manager.create(any(DataSource.class), anyLong(), anyString(), anyString()))
            .thenReturn(null);

        expectedException.expect(OperationFailedI18nException.class);

        object.createTeam(sampleTeam);
    }

    @Test
    public void shouldFailWhenTeamCannotBeUpdatedOnManager(){
        com.m1.af.api.model.team.Team sampleTeam = createTeam(0L, SAMPLE_TEAM_NAME);
        sampleTeam.setParentId(PARENT_TEAM_ID);
        setupForCreateTeam();
        when(manager.update(any(List.class))).thenReturn(0);

        expectedException.expect(OperationFailedI18nException.class);

        object.createTeam(sampleTeam);
    }

    @Test
    public void shouldAddCreatedTeamToParentsSubteams() {
        com.m1.af.api.model.team.Team sampleTeam = createTeam(0L, SAMPLE_TEAM_NAME);
        sampleTeam.setParentId(PARENT_TEAM_ID);
        setupForCreateTeam();

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);

        object.createTeam(sampleTeam);
        verify(eventLogger, times(1)).logEvent(CAP_TEAM, EVENT_CREATE, userContext, sampleTeam.getName());

        verify(manager, times(2)).update(captor.capture());
        Team parent = (Team) captor.getAllValues().get(1).get(0);
        assertThat(parent.getSubteams()).contains(sampleTeam.getId());
    }

    @Test
    public void shouldCreateNewTeamAndAssignId() {
        com.m1.af.api.model.team.Team sampleTeam = createTeam(0L, SAMPLE_TEAM_NAME);
        sampleTeam.setParentId(PARENT_TEAM_ID);
        setupForCreateTeam();

        com.m1.af.api.model.team.Team createdTeam = object.createTeam(sampleTeam);
        verify(eventLogger, times(1)).logEvent(CAP_TEAM, EVENT_CREATE, userContext, createdTeam.getName());
        assertThat(createdTeam.getId()).isEqualTo(SAMPLE_TEAM_ID);
    }

    @Test
    public void shouldGetPermissionDefaultsForAdmins() {
        when(user.getRole()).thenReturn(ROLE_CUST_ADMIN);

        DefaultTeamLeaderPermissions permissions = object.getPermissionDefaults();

        assertThat(permissions.getActorPermissions()).isEqualTo(UiSettings.BIT_FIELD_ALL);
    }

    @Test
    public void shouldGetPermissionDefaultsForCustomerUser() {
        when(user.getRole()).thenReturn(IUserManager.ROLE_CUST_USER);

        TeamMapCache cache = mock(TeamMapCache.class);
        when(object.getTeamMapCache()).thenReturn(cache);
        when(cache.getUserPermissions(anyLong(), anyLong())).thenReturn(UiSettings.BIT_FLAG_MANAGE_TEAMS); // random permission for test

        DefaultTeamLeaderPermissions permissions = object.getPermissionDefaults();

        assertThat(permissions.getActorPermissions()).isEqualTo(UiSettings.BIT_FLAG_MANAGE_TEAMS);
    }

    @Test
    public void givenTeamIdsWhenResolveTeamsDoesCallTeamManager() {
        //Arrange
        ImmutableSet<Long> teamIds = ImmutableSet.of(SAMPLE_TEAM_ID);

        //Act
        object.resolveTeams(teamIds);

        //Assert
        verify(manager).find(constraintCaptor.capture());
        assertEquals(teamIds, constraintCaptor.getValue().get(0).getValue());
    }

    @Test
    public void shouldThrowExceptionWhenDeleteNonexistingTeam() {
        when(manager.getTeam(SAMPLE_TEAM_ID)).thenReturn(null);
        expectedException.expect(ResourceNotFoundI18nException.class);

        object.deleteTeam(SAMPLE_TEAM_ID);
    }


    @Test
    public void shouldCloseIncidentsWhenDeleteTeam() throws Exception {
        Team mockTeam = mockTeam(SAMPLE_TEAM_ID, SAMPLE_TEAM_NAME);
        when(manager.getTeam(SAMPLE_TEAM_ID)).thenReturn(mockTeam);
        TeamMapCache cache = mock(TeamMapCache.class);
        when(object.getTeamMapCache()).thenReturn(cache);

        Incident incident = mock(Incident.class);
        when(incidentManager.find(anyList())).thenReturn(Collections.singletonList(incident));

        when(cache.getParents(SAMPLE_TEAM_ID)).thenReturn(Collections.singletonList(SUB_TEAM_ID));
        when(incidentManager.update(anyList())).thenReturn(1);

        when(manager.recursiveDeleteTeam(eq(SAMPLE_TEAM_ID), anyInt())).thenReturn(1);
        object.deleteTeam(SAMPLE_TEAM_ID);

        verify(eventLogger, times(1)).logEvent(CAP_TEAM, EVENT_DELETE, userContext, mockTeam.getName());
        verify(incident).setArchived(true);
        verify(incidentManager).update(Collections.singletonList(incident));
    }

    private void setupForDelete(){
        Team mockTeam = mockTeam(SAMPLE_TEAM_ID, SAMPLE_TEAM_NAME);
        when(manager.getTeam(SAMPLE_TEAM_ID)).thenReturn(mockTeam);
        TeamMapCache cache = mock(TeamMapCache.class);
        when(object.getTeamMapCache()).thenReturn(cache);

        when(incidentManager.find(anyList())).thenReturn(Collections.emptyList());
    }

    @Test
    public void shouldThrowExceptionIfDeleteFails1() throws Exception {
        setupForDelete();

        when(manager.recursiveDeleteTeam(eq(SAMPLE_TEAM_ID), anyInt())).thenReturn(0);

        expectedException.expect(OperationFailedI18nException.class);
        object.deleteTeam(SAMPLE_TEAM_ID);
    }


    @Test
    public void shouldThrowExceptionIfDeleteFails2() throws Exception {
        setupForDelete();

        when(manager.recursiveDeleteTeam(eq(SAMPLE_TEAM_ID), anyInt())).thenThrow(SQLException.class);

        expectedException.expect(OperationFailedI18nException.class);
        object.deleteTeam(SAMPLE_TEAM_ID);
    }


    @Test
    public void shouldThrowExceptionIfDeleteFails3() throws Exception {
        setupForDelete();

        when(manager.recursiveDeleteTeam(eq(SAMPLE_TEAM_ID), anyInt())).thenThrow(Exception.class);

        expectedException.expect(OperationFailedI18nException.class);
        object.deleteTeam(SAMPLE_TEAM_ID);
    }

    @Test
    public void shouldThrowExceptionWhenDeleteTeamGreetingForNonExistingTeam() {
        mockTeamMapCacheForParentTeam();

        when(manager.getTeam(SAMPLE_TEAM_ID)).thenReturn(null);
        expectedException.expect(ResourceNotFoundI18nException.class);

        object.deleteTeamGreeting(SAMPLE_TEAM_ID);
    }

    @Test
    public void shouldDeleteTeamGreeting() {
        Team mockTeam = mockTeam(SAMPLE_TEAM_ID, SAMPLE_TEAM_NAME);
        TeamMapCache cache = mock(TeamMapCache.class);
        when(object.getTeamMapCache()).thenReturn(cache);
        when(cache.get(SAMPLE_TEAM_ID)).thenReturn(mockTeam);

        object.deleteTeamGreeting(SAMPLE_TEAM_ID);

        ArgumentCaptor<TeamCapabilities> capabilitiesArgumentCaptor = ArgumentCaptor.forClass(TeamCapabilities.class);
        verify(manager).saveTeamCapabilities(mockTeam);
        verify(mockTeam).setTeamCapabilities(capabilitiesArgumentCaptor.capture());
        assertThat(capabilitiesArgumentCaptor.getValue().getCapabilityValue(CAP_FROM_TEAMEMAIL_ADDR)).isNull();
        assertThat(capabilitiesArgumentCaptor.getValue().getCapabilityValue(CAP_FROM_TEAMVOICENOTIFICATIONSYSTEM)).isNull();

    }

    @Test
    public void shouldUpdateTeamMemberDescriptions(){
        long userMemberId = 1, bgMemberId = 2, egMemberId = 3, dgMemberId = 4;
        String userDescription = "UserDesc", bgDescription = "BGDesc", egDescription = "EGDesc", dgDescription = "DGDesc";

        User user = new User(userMemberId);
        user.setDescription(userDescription);

        BroadcastGroup bcGroup = new BroadcastGroup();
        bcGroup.setId(bgMemberId);
        bcGroup.setDescription(bgDescription);

        EscalationGroup eGroup = new EscalationGroup();
        eGroup.setId(egMemberId);
        eGroup.setDescription(egDescription);

        DynamicGroup dGroup = new DynamicGroup();
        dGroup.setId(dgMemberId);
        dGroup.setDescription(dgDescription);


        TeamMember userMember = new TeamMember(MemberType.USER, userMemberId, "", null, true);
        TeamMember bgMember = new TeamMember(MemberType.BROADCAST_GROUP, bgMemberId, "", null, true);
        TeamMember dgMember = new TeamMember(MemberType.DYNAMIC_GROUP, dgMemberId, "", null, true);
        TeamMember egMember = new TeamMember(MemberType.ESCALATION_GROUP, egMemberId, "", null, true);

        com.m1.af.api.model.team.Team team = createTeam(SAMPLE_TEAM_ID, SAMPLE_TEAM_NAME);
        team.setMembers(Arrays.asList(userMember, bgMember, dgMember, egMember));

        when(userManager.findUsers(anyList())).thenReturn(Collections.singletonList(user));
        when(broadcastGroupManager.find(anyList())).thenReturn(Collections.singletonList(bcGroup));
        when(dynamicGroupManager.find(anyList())).thenReturn(Collections.singletonList(dGroup));
        when(escalationGroupManager.find(anyList())).thenReturn(Collections.singletonList(eGroup));

        object.populateTeamMemberDescriptions(team);

        assertThat(team.getMembers().stream()
            .filter(m -> MemberType.USER.equals(m.getType()))
            .collect(Collectors.toList()).get(0).getDescription()).isEqualTo(userDescription);

        assertThat(team.getMembers().stream()
            .filter(m -> MemberType.BROADCAST_GROUP.equals(m.getType()))
            .collect(Collectors.toList()).get(0).getDescription()).isEqualTo(bgDescription);

        assertThat(team.getMembers().stream()
            .filter(m -> MemberType.ESCALATION_GROUP.equals(m.getType()))
            .collect(Collectors.toList()).get(0).getDescription()).isEqualTo(egDescription);

        assertThat(team.getMembers().stream()
            .filter(m -> MemberType.DYNAMIC_GROUP.equals(m.getType()))
            .collect(Collectors.toList()).get(0).getDescription()).isEqualTo(dgDescription);
    }

    @Test
    public void shouldWriteTeamAsCsv(){
        mockTeamMapCacheForParentTeam();

        Team mockTeam = mockTeam(SAMPLE_TEAM_ID, SAMPLE_TEAM_NAME);
        when(manager.getTeam(SAMPLE_TEAM_ID)).thenReturn(mockTeam);
        PrintWriter writer = mock(PrintWriter.class);

        object.writeTeamAsCsv(SAMPLE_TEAM_ID, writer);

        verify(userManager).findUsers(anyList());
        verify(writer, atLeastOnce()).println();
    }

    @Test
    public void shouldGenerateReportFileName() {
        Team mockTeam = mockTeam(SAMPLE_TEAM_ID, SAMPLE_TEAM_NAME);
        when(manager.getTeam(SAMPLE_TEAM_ID)).thenReturn(mockTeam);

        String fileName = object.getTeamDetailReportFileName(SAMPLE_TEAM_ID);

        assertThat(fileName).isEqualTo(SAMPLE_TEAM_NAME + FILE_EXTENSION_CSV);
    }

    @Test
    public void shouldNotLetDuplicateTeamNamesForACustomer() {
        com.m1.af.api.model.team.Team team = createTeam(SAMPLE_TEAM_ID, SAMPLE_TEAM_NAME);
        Team existingTeam = mockTeam(SAMPLE_TEAM_ID + 1, SAMPLE_TEAM_NAME);
        when(manager.find(anyList())).thenReturn(Collections.singletonList(existingTeam));
        expectedException.expect(InvalidInputI18nException.class);
        object.updateTeam(team);
    }

    @Test
    public void shouldThrowErrorIfTeamDoesNotExists() {
        com.m1.af.api.model.team.Team team = createTeam(SAMPLE_TEAM_ID, SAMPLE_TEAM_NAME);
        Team existingTeam = mockTeam(SAMPLE_TEAM_ID, SAMPLE_TEAM_NAME);
        when(manager.find(anyList())).thenReturn(Collections.singletonList(existingTeam));
        when(manager.get(SAMPLE_TEAM_ID)).thenReturn(null);
        expectedException.expect(ResourceNotFoundI18nException.class);

        object.updateTeam(team);
    }


    @Test
    public void shouldPopulateLegacyTeamWithFormData() {
        String description = "description";
        com.m1.af.api.model.team.Team team = createTeam(SAMPLE_TEAM_ID, SAMPLE_TEAM_NAME);
        team.setDescription(description);

        Team existingTeam = mockTeam(SAMPLE_TEAM_ID, SAMPLE_TEAM_NAME);
        when(manager.find(anyList())).thenReturn(Collections.singletonList(existingTeam));
        when(manager.get(SAMPLE_TEAM_ID)).thenReturn(existingTeam);
        when(manager.update(anyList())).thenReturn(1);

        object.updateTeam(team);

        verify(existingTeam).setName(SAMPLE_TEAM_NAME);
        verify(existingTeam).setDescription(description);
    }

    @Test
    public void shouldUpdateExistingTeamWithNewMembers() {
        com.m1.af.api.model.team.Team team = createTeam(SAMPLE_TEAM_ID, SAMPLE_TEAM_NAME);
        Team existingTeam = mockTeam(SAMPLE_TEAM_ID, SAMPLE_TEAM_NAME);
        when(existingTeam.getMembers()).thenReturn(Arrays.asList(
            new com.m1.cmc.mgmt.TeamMember(SAMPLE_TEAM_ID, MemberType.BROADCAST_GROUP, 1L, "", true),
            new com.m1.cmc.mgmt.TeamMember(SAMPLE_TEAM_ID, MemberType.BROADCAST_GROUP, 2L, "", true)
        ));
        team.setMembers(Arrays.asList(
            new TeamMember(MemberType.BROADCAST_GROUP, 2L, "", "", true),
            new TeamMember(MemberType.USER, 3L, "", "", true)
        ));
        when(manager.find(anyList())).thenReturn(Collections.singletonList(existingTeam));
        when(manager.get(SAMPLE_TEAM_ID)).thenReturn(existingTeam);
        when(manager.update(anyList())).thenReturn(1);

        object.updateTeam(team);
        verify(eventLogger, times(1)).logEvent(CAP_TEAM, EVENT_UPDATE, userContext, team.getName());

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(existingTeam).setMembers(captor.capture());
        assertThat(captor.getValue()).extracting("memberId").containsOnly(2L, 3L);
    }


    @Test
    public void shouldSetExistingTeamMembersToNullIfMemberListNotChanged() {
        com.m1.af.api.model.team.Team team = createTeam(SAMPLE_TEAM_ID, SAMPLE_TEAM_NAME);
        Team existingTeam = mockTeam(SAMPLE_TEAM_ID, SAMPLE_TEAM_NAME);
        when(existingTeam.getMembers()).thenReturn(Arrays.asList(
            new com.m1.cmc.mgmt.TeamMember(SAMPLE_TEAM_ID, MemberType.BROADCAST_GROUP, 1L, "", true),
            new com.m1.cmc.mgmt.TeamMember(SAMPLE_TEAM_ID, MemberType.BROADCAST_GROUP, 2L, "", true)
        ));
        team.setMembers(Arrays.asList(
            new TeamMember(MemberType.BROADCAST_GROUP, 1L, "", "", true),
            new TeamMember(MemberType.BROADCAST_GROUP, 2L, "", "", true)
        ));
        when(manager.find(anyList())).thenReturn(Collections.singletonList(existingTeam));
        when(manager.get(SAMPLE_TEAM_ID)).thenReturn(existingTeam);
        when(manager.update(anyList())).thenReturn(1);

        object.updateTeam(team);

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(existingTeam).setMembers(captor.capture());
        assertThat(captor.getValue()).isNull();
    }


    @Test
    public void shouldUpdateExistingTeamWithNewLeaders() {
        com.m1.af.api.model.team.Team team = createTeam(SAMPLE_TEAM_ID, SAMPLE_TEAM_NAME);
        Team existingTeam = mockTeam(SAMPLE_TEAM_ID, SAMPLE_TEAM_NAME);
        when(existingTeam.getLeaders()).thenReturn(Arrays.asList(
            new com.m1.cmc.mgmt.TeamLeader( SAMPLE_TEAM_ID, 1L, PERMISSION_PARENT),
            new com.m1.cmc.mgmt.TeamLeader( SAMPLE_TEAM_ID, 2L, PERMISSION_PARENT)
        ));
        team.setLeaders(Arrays.asList(
            new TeamLeaderUserInfo( 2L, "", "", PERMISSION_SUB, 0, true, true, true),
            new TeamLeaderUserInfo( 3L, "", "", PERMISSION_SUB, 0, true, true, true)
        ));
        when(manager.find(anyList())).thenReturn(Collections.singletonList(existingTeam));
        when(manager.get(SAMPLE_TEAM_ID)).thenReturn(existingTeam);
        when(manager.update(anyList())).thenReturn(1);

        object.updateTeam(team);

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(existingTeam).setLeaders(captor.capture());
        assertThat(captor.getValue()).extracting("userId").containsOnly(2L, 3L);
    }

    @Test
    public void shouldSetExistingTeamLeadersToNullIfLeadersNotChanged() {
        com.m1.af.api.model.team.Team team = createTeam(SAMPLE_TEAM_ID, SAMPLE_TEAM_NAME);
        Team existingTeam = mockTeam(SAMPLE_TEAM_ID, SAMPLE_TEAM_NAME);
        when(existingTeam.getLeaders()).thenReturn(Arrays.asList(
            new com.m1.cmc.mgmt.TeamLeader( SAMPLE_TEAM_ID, 1L, PERMISSION_PARENT),
            new com.m1.cmc.mgmt.TeamLeader( SAMPLE_TEAM_ID, 2L, PERMISSION_PARENT)
        ));
        team.setLeaders(Arrays.asList(
            new TeamLeaderUserInfo( 1L, "", "", PERMISSION_PARENT, 0, true, true, true),
            new TeamLeaderUserInfo( 2L, "", "", PERMISSION_PARENT, 0, true, true, true)
        ));
        when(manager.find(anyList())).thenReturn(Collections.singletonList(existingTeam));
        when(manager.get(SAMPLE_TEAM_ID)).thenReturn(existingTeam);
        when(manager.update(anyList())).thenReturn(1);

        object.updateTeam(team);

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(existingTeam).setLeaders(captor.capture());
        assertThat(captor.getValue()).isNull();
    }


    @Test
    public void updateTeamShouldFailIfLegacyUpdateFails() {
        com.m1.af.api.model.team.Team team = createTeam(SAMPLE_TEAM_ID, SAMPLE_TEAM_NAME);
        Team existingTeam = mockTeam(SAMPLE_TEAM_ID, SAMPLE_TEAM_NAME);
        when(manager.find(anyList())).thenReturn(Collections.singletonList(existingTeam));
        when(manager.get(SAMPLE_TEAM_ID)).thenReturn(existingTeam);
        when(manager.update(anyList())).thenReturn(0);

        expectedException.expect(OperationFailedI18nException.class);

        object.updateTeam(team);
    }

    @Test
    public void shouldScheduleUserListUpdateIfMembersChange() {
        com.m1.af.api.model.team.Team team = createTeam(SAMPLE_TEAM_ID, SAMPLE_TEAM_NAME);
        Team existingTeam = mockTeam(SAMPLE_TEAM_ID, SAMPLE_TEAM_NAME);
        when(existingTeam.getMembers()).thenReturn(Arrays.asList(
            new com.m1.cmc.mgmt.TeamMember(SAMPLE_TEAM_ID, MemberType.BROADCAST_GROUP, 1L, "", true),
            new com.m1.cmc.mgmt.TeamMember(SAMPLE_TEAM_ID, MemberType.BROADCAST_GROUP, 2L, "", true)
        ));
        team.setMembers(Arrays.asList(
            new TeamMember(MemberType.BROADCAST_GROUP, 2L, "", "", true),
            new TeamMember(MemberType.USER, 3L, "", "", true)
        ));
        when(manager.find(anyList())).thenReturn(Collections.singletonList(existingTeam));
        when(manager.get(SAMPLE_TEAM_ID)).thenReturn(existingTeam);
        when(manager.update(anyList())).thenReturn(1);

        object.updateTeam(team);
        verify(eventLogger, times(1)).logEvent(CAP_TEAM, EVENT_UPDATE, userContext, team.getName());

        verify(manager).scheduleUpdateUserList(anyList());
    }

    @Test
    public void shouldNotScheduleUserListUpdateIfMembersNotChanged() {
        com.m1.af.api.model.team.Team team = createTeam(SAMPLE_TEAM_ID, SAMPLE_TEAM_NAME);
        Team existingTeam = new Team();//mockTeam(SAMPLE_TEAM_ID, SAMPLE_TEAM_NAME);
        existingTeam.setId(SAMPLE_TEAM_ID);
        existingTeam.setName(SAMPLE_TEAM_NAME);
        existingTeam.setMembers(Arrays.asList(
            new com.m1.cmc.mgmt.TeamMember(SAMPLE_TEAM_ID, MemberType.BROADCAST_GROUP, 1L, "", true),
            new com.m1.cmc.mgmt.TeamMember(SAMPLE_TEAM_ID, MemberType.BROADCAST_GROUP, 2L, "", true)
        ));
        team.setMembers(Arrays.asList(
            new TeamMember(MemberType.BROADCAST_GROUP, 1L, "", "", true),
            new TeamMember(MemberType.BROADCAST_GROUP, 2L, "", "", true)
        ));
        when(manager.find(anyList())).thenReturn(Collections.singletonList(existingTeam));
        when(manager.get(SAMPLE_TEAM_ID)).thenReturn(existingTeam);
        when(manager.update(anyList())).thenReturn(1);

        object.updateTeam(team);

        verify(manager, times(0)).scheduleUpdateUserList(anyList());
    }

    @Test
    public void shouldNotUpdateTeamGreetingIfTeamNotFound() {
        TeamMapCache cache = mock(TeamMapCache.class);
        when(object.getTeamMapCache()).thenReturn(cache);
        when(cache.get(anyLong())).thenReturn(null);

        expectedException.expect(ResourceNotFoundI18nException.class);

        object.updateTeamGreeting(createGreeting("abc@email.com", "test"), SAMPLE_TEAM_ID);
    }

    @Test
    public void shouldNotUpdateTeamGreetingIfEmailIsNotValid() {
        TeamMapCache cache = mock(TeamMapCache.class);
        when(object.getTeamMapCache()).thenReturn(cache);
        when(cache.get(anyLong())).thenReturn(createLegacyTeam(SAMPLE_TEAM_ID, SAMPLE_TEAM_NAME));

        expectedException.expect(InvalidInputI18nException.class);

        object.updateTeamGreeting(createGreeting("abc", "test"), 1);
    }

    @Test
    public void shouldUpdateTeamGreetingIfEmailIsValid() {
        TeamGreeting greeting = createGreeting("abc@email.com", "test");
        TeamMapCache cache = mock(TeamMapCache.class);
        when(object.getTeamMapCache()).thenReturn(cache);
        when(cache.get(anyLong())).thenReturn(createLegacyTeam(SAMPLE_TEAM_ID, SAMPLE_TEAM_NAME));

        object.updateTeamGreeting(greeting, SAMPLE_TEAM_ID);

        ArgumentCaptor<Team> captor = ArgumentCaptor.forClass(Team.class);
        verify(manager).saveTeamCapabilities(captor.capture());
        TeamCapabilities capabilities = captor.getValue().getTeamCapabilities();
        assertThat(capabilities.getCapabilityValue(TeamCapabilities.CAP_FROM_TEAMEMAIL_ADDR)).isEqualTo(greeting.getFromEmail());
        assertThat(capabilities.getCapabilityValue(TeamCapabilities.CAP_FROM_TEAMVOICENOTIFICATIONSYSTEM))
            .isEqualTo(greeting.getVoiceSystemName());
    }

    private TeamGreeting createGreeting(String email, String voiceName) {
        TeamGreeting greeting = new TeamGreeting();
        greeting.setFromEmail(email);
        greeting.setVoiceSystemName(voiceName);
        return greeting;
    }

    private static com.m1.af.api.model.team.Team createTeam(Long id, String name) {
        com.m1.af.api.model.team.Team team = new com.m1.af.api.model.team.Team();
        team.setId(id);
        team.setName(name);
        return team;
    }

    private static Team createLegacyTeam(Long id, String name) {
        Team team = new Team();
        team.setId(id);
        team.setName(name);
        return team;
    }

    private static TeamLeader createLegacyTeamLeader(long userId, long teamId, long permissions){
        TeamLeader leader = new TeamLeader();
        leader.setUserId(userId);
        leader.setTeamId(teamId);
        leader.setPermissions(permissions);
        return leader;
    }

    @Test
    public void findManagedTeamIds_supportUser() {
        User user = mock(User.class);
        assertTrue(object.getManagedTeamIds(user).isEmpty());
    }

    @Test
    public void findManagedTeamIds_leaderUser() {
        setupForTeamListing();
        doReturn(null).when(object).leaderOfTeams(customerUserToListTeams);
        object.getManagedTeamIds(customerUserToListTeams);
        verify(object).leaderOfTeams(customerUserToListTeams);
    }

    @Test
    public void leaderOfTeams() {
        setupForTeamListing();
        when(TEAM.getLeader(customerUserToListTeams.getId())).thenReturn(null);
        Set<Long> ids = object.leaderOfTeams(customerUserToListTeams);
        assertEquals(Collections.singleton(SUB_TEAM_ID), ids);
    }

    @Test
    public void leaderOfTeams_globalLeader() {
        setupForTeamListing();
        TeamLeader leader = new TeamLeader();
        when(TEAM.getLeader(customerUserToListTeams.getId())).thenReturn(leader);
        Set<Long> ids = object.leaderOfTeams(customerUserToListTeams);
        assertEquals(new HashSet<>(Arrays.asList(TEAM.getId(), SUB_TEAM_ID)), ids);
    }

    @Test
    public void leaderOfTeams_emptyLeaderMap() {
        setupForTeamListing();
        when(customerUserToListTeams.getLeaderOfTeamsMap()).thenReturn(null);
        object.leaderOfTeams(customerUserToListTeams);
        verify(manager).populateLeaderOfTeamMap(customerUserToListTeams);
    }

}