package sonia.scm.pathwp.service;

import com.github.sdorra.shiro.ShiroRule;
import com.github.sdorra.shiro.SubjectAware;
import org.apache.shiro.util.ThreadContext;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import sonia.scm.group.GroupNames;
import sonia.scm.repository.Repository;
import sonia.scm.repository.RepositoryTestData;
import sonia.scm.store.ConfigurationStore;
import sonia.scm.store.ConfigurationStoreFactory;
import sonia.scm.store.InMemoryConfigurationStoreFactory;
import sonia.scm.user.User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
@SubjectAware(configuration = "classpath:sonia/scm/pathwp/shiro-001.ini", username = "user_1", password = "secret")
public class PathWritePermissionServiceTest {

  public static final String MAIL = "email@d.de";
  public static final String USERNAME = "user_1";
  public static final User USER = new User(USERNAME, "User 1", MAIL);
  public static final String PATH = "dir1/subDir/file1.txt";
  public static final PathWritePermission.Type TYPE = PathWritePermission.Type.ALLOW;
  public static final boolean GROUP = false;
  public static final String GROUP_NAME = "group1";

  @Rule
  public ShiroRule shiro = new ShiroRule();

  @Mock
  ConfigurationStore<PathWritePermissions> store;

  ConfigurationStoreFactory storeFactory;


  PathWritePermissionService service;
  public static final Repository REPOSITORY = RepositoryTestData.createHeartOfGold();

  @Before
  public void init() {
    storeFactory = new InMemoryConfigurationStoreFactory(store);
    service = new PathWritePermissionService(storeFactory, null);
  }

  public PathWritePermissionServiceTest() {
    // cleanup state that might have been left by other tests
    ThreadContext.unbindSecurityManager();
    ThreadContext.unbindSubject();
    ThreadContext.remove();
  }

  @Test
  @SubjectAware(username = "owner", password = "secret")
  public void shouldStorePermissionForOwner() {
    PathWritePermissions permissions = new PathWritePermissions();

    PathWritePermission permission = createPathWritePermission();
    permissions.getPermissions().add(permission);
    service.setPermissions(REPOSITORY, permissions);

    verify(store).set(argThat(argPermissions -> {
      assertThat(argPermissions.getPermissions()).hasSize(1);
      assertThat(argPermissions.getPermissions().get(0))
        .isEqualToComparingFieldByField(createPathWritePermission());
      return true;
    }));
  }

  @Test
  public void shouldFailOnStoringPermissionForNotAdminOrOwnerUsers() {
    PathWritePermissions permissions = new PathWritePermissions();

    PathWritePermission permission = createPathWritePermission();
    permissions.getPermissions().add(permission);

    assertThatThrownBy(() -> service.setPermissions(REPOSITORY, permissions)).hasMessage("Subject does not have permission [repository:modify:id-1]");

    verify(store, never()).set(any());
  }

  @Test
  @SubjectAware(username = "owner", password = "secret")
  public void shouldAllowRepositoryOwnerWithoutReadingPermissions() {
    User admin = new User("owner");
    admin.setAdmin(false);
    boolean privileged = service.isPrivileged(admin, new GroupNames(GROUP_NAME), REPOSITORY, PATH);

    assertThat(privileged).isTrue();
    verify(store, never()).get();
  }

  @Test
  @SubjectAware(username = "admin", password = "secret")
  public void shouldAllowAdminWithoutReadingPermissions() {
    User admin = new User("admin");
    admin.setAdmin(true);
    boolean privileged = service.isPrivileged(admin, new GroupNames(GROUP_NAME), REPOSITORY, PATH);

    assertThat(privileged).isTrue();
    verify(store, never()).get();
  }

  @Test
  public void shouldAllowAnyUserIfTheConfigIsDisabled() {
    PathWritePermissions permissions = new PathWritePermissions();
    permissions.setEnabled(false);
    when(store.get()).thenReturn(permissions);
    boolean privileged = service.isPrivileged(USER, new GroupNames(GROUP_NAME), REPOSITORY, PATH);

    assertThat(privileged).isTrue();
    verify(store).get();
  }

  @Test
  public void shouldPrivilegeUserBecauseThePathIsAllowedToTheUser() {
    PathWritePermissions permissions = new PathWritePermissions();
    permissions.getPermissions().add(createPathWritePermission());
    when(store.get()).thenReturn(permissions);

    boolean privileged = service.isPrivileged(USER, new GroupNames(GROUP_NAME), REPOSITORY, PATH);

    assertThat(privileged).isTrue();
  }

  @Test
  public void shouldPrivilegeUserBecauseAllPathsAreAllowedToTheUser() {
    PathWritePermissions permissions = new PathWritePermissions();
    PathWritePermission pathWritePermission = new PathWritePermission("*", USER.getName(), false, PathWritePermission.Type.ALLOW);
    permissions.getPermissions().add(pathWritePermission);
    when(store.get()).thenReturn(permissions);

    boolean privileged = service.isPrivileged(USER, new GroupNames(GROUP_NAME), REPOSITORY, PATH);

    assertThat(privileged).isTrue();
  }

  @Test
  public void shouldPrivilegeUserBecauseAllPathsAreAllowedToOneOfHisGroups() {
    PathWritePermissions permissions = new PathWritePermissions();
    PathWritePermission pathWritePermission = new PathWritePermission("*", GROUP_NAME, true, PathWritePermission.Type.ALLOW);
    permissions.getPermissions().add(pathWritePermission);
    when(store.get()).thenReturn(permissions);

    boolean privileged = service.isPrivileged(USER, new GroupNames(GROUP_NAME, "group2", "group3"), REPOSITORY, PATH);

    assertThat(privileged).isTrue();
  }

  @Test
  public void shouldPrivilegeUserBecauseTheSearchedPathIsAllowedToOneOfHisGroups() {
    PathWritePermissions permissions = new PathWritePermissions();
    PathWritePermission pathWritePermission = new PathWritePermission(PATH, GROUP_NAME, true, PathWritePermission.Type.ALLOW);
    permissions.getPermissions().add(pathWritePermission);
    when(store.get()).thenReturn(permissions);

    boolean privileged = service.isPrivileged(USER, new GroupNames(GROUP_NAME, "group2", "group3"), REPOSITORY, PATH);

    assertThat(privileged).isTrue();
  }

  @Test
  public void shouldDenyPermissionBecauseAllPathsAreAllowedToTheUserButTheSearchedPathIsDenied() {
    PathWritePermissions permissions = new PathWritePermissions();
    PathWritePermission pathWritePermission = new PathWritePermission("*", USER.getName(), false, PathWritePermission.Type.ALLOW);
    PathWritePermission deniedPermission = new PathWritePermission(PATH, USER.getName(), false, PathWritePermission.Type.DENY);
    permissions.getPermissions().add(pathWritePermission);
    permissions.getPermissions().add(deniedPermission);
    when(store.get()).thenReturn(permissions);

    boolean privileged = service.isPrivileged(USER, new GroupNames(GROUP_NAME), REPOSITORY, PATH);

    assertThat(privileged).isFalse();
  }

  @Test
  public void shouldDenyPermissionBecauseAllPathsAreAllowedToOneOfTheUserGroupsButTheSearchedPathIsDenied() {
    PathWritePermissions permissions = new PathWritePermissions();
    PathWritePermission pathWritePermission = new PathWritePermission("*", GROUP_NAME, true, PathWritePermission.Type.ALLOW);
    PathWritePermission deniedPermission = new PathWritePermission(PATH, USER.getName(), false, PathWritePermission.Type.DENY);
    permissions.getPermissions().add(pathWritePermission);
    permissions.getPermissions().add(deniedPermission);
    when(store.get()).thenReturn(permissions);

    boolean privileged = service.isPrivileged(USER, new GroupNames(GROUP_NAME, "group2", "group3"), REPOSITORY, PATH);

    assertThat(privileged).isFalse();
  }

  @Test
  public void shouldDenyPermissionBecauseTheSearchedPathIsDenied() {
    PathWritePermissions permissions = new PathWritePermissions();
    PathWritePermission deniedPermission = new PathWritePermission(PATH, USER.getName(), false, PathWritePermission.Type.DENY);
    permissions.getPermissions().add(deniedPermission);
    when(store.get()).thenReturn(permissions);

    boolean privileged = service.isPrivileged(USER, new GroupNames(GROUP_NAME), REPOSITORY, PATH);

    assertThat(privileged).isFalse();
  }

  @Test
  public void shouldDenyPermissionBecauseTheSearchedPathIsDeniedToOneOfTheUserGroups() {
    PathWritePermissions permissions = new PathWritePermissions();
    PathWritePermission deniedPermission = new PathWritePermission(PATH, GROUP_NAME, true, PathWritePermission.Type.DENY);
    permissions.getPermissions().add(deniedPermission);
    when(store.get()).thenReturn(permissions);

    boolean privileged = service.isPrivileged(USER, new GroupNames(GROUP_NAME, "group2", "group3"), REPOSITORY, PATH);

    assertThat(privileged).isFalse();
  }

  @Test
  public void shouldDenyPermissionBecauseThereIsNoStoredPermissionForTheSearchedPath() {
    PathWritePermissions permissions = new PathWritePermissions();
    PathWritePermission deniedPermission = new PathWritePermission("other_path", USER.getName(), false, PathWritePermission.Type.ALLOW);
    permissions.getPermissions().add(deniedPermission);
    when(store.get()).thenReturn(permissions);

    boolean privileged = service.isPrivileged(USER, new GroupNames(GROUP_NAME), REPOSITORY, PATH);

    assertThat(privileged).isFalse();
  }

  private PathWritePermission createPathWritePermission() {
    return new PathWritePermission(PATH, USER.getName(), GROUP, TYPE);
  }
}