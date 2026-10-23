/* hack defines its own getdate(), which predates the POSIX one: declare <time.h>'s first,
 * then give hack's a different name. */
#include <time.h>
#define getdate hack_getdate
