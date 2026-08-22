// FIXTURE — must trip khandaq-objc-format-string-from-variable. Never compiled, never shipped.
#import <Foundation/Foundation.h>

void khandaqFixtureFormatString(NSString *peerSuppliedName)
{
    NSLog(peerSuppliedName);
    NSString *s = [NSString stringWithFormat:peerSuppliedName, 1];
    (void)s;
}
